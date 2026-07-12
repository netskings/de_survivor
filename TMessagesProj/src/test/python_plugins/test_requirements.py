import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from plugin_runtime.errors import RequirementSecurityError
from plugin_runtime.requirements import PurePythonRequirementInstaller, install_pure_wheel_atomic, validate_pure_wheel


def wheel_bytes(name, version="1.0", requires=(), extra_files=None, tag="py3-none-any"):
    distribution = name.replace("-", "_")
    dist_info = f"{distribution}-{version}.dist-info"
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(f"{distribution}/__init__.py", "VALUE = 1\n")
        archive.writestr(f"{dist_info}/WHEEL", f"Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: {tag}\n")
        metadata = f"Metadata-Version: 2.1\nName: {name}\nVersion: {version}\nRequires-Python: >=3.8\n"
        for value in requires:
            metadata += f"Requires-Dist: {value}\n"
        archive.writestr(f"{dist_info}/METADATA", metadata + "\n")
        archive.writestr(f"{dist_info}/RECORD", "")
        for path, value in (extra_files or {}).items():
            archive.writestr(path, value)
    return output.getvalue(), f"{distribution}-{version}-{tag}.whl"


class FakeResponse(io.BytesIO):
    def __enter__(self): return self
    def __exit__(self, *args): self.close()


class RequirementsTests(unittest.TestCase):
    @staticmethod
    def resolver_for(project_wheels, directory):
        projects, artifacts = {}, {}
        for name, payload, filename in project_wheels:
            url = f"https://files.pythonhosted.org/packages/{filename}"
            projects[name] = {"releases": {"1.0": [{
                "packagetype": "bdist_wheel", "filename": filename, "url": url,
                "digests": {"sha256": hashlib.sha256(payload).hexdigest()},
                "requires_python": ">=3.8",
            }]}}
            artifacts[url] = payload
        def opener(request, timeout=0):
            if request.full_url.startswith("https://pypi.org/pypi/"):
                return FakeResponse(json.dumps(projects[request.full_url.split("/")[-2]]).encode())
            return FakeResponse(artifacts[request.full_url])
        return PurePythonRequirementInstaller(directory, opener=opener)

    def test_safe_universal_wheel_installs_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            payload, filename = wheel_bytes("demo")
            wheel = Path(directory, filename)
            wheel.write_bytes(payload)
            validated = validate_pure_wheel(wheel)
            installed = install_pure_wheel_atomic(wheel, Path(directory, "libs"))
            self.assertEqual(validated.install_directory_name, installed.name)
            self.assertTrue((installed / "demo" / "__init__.py").is_file())

    def test_rejects_native_traversal_pth_and_setup(self):
        cases = [
            ("native.so", b"x"), ("../escape.py", b"x"),
            ("evil.pth", b"import os"), ("setup.py", b"raise SystemExit"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            for index, extra in enumerate(cases):
                payload, filename = wheel_bytes(f"bad{index}", extra_files={extra[0]: extra[1]})
                wheel = Path(directory, filename)
                wheel.write_bytes(payload)
                with self.subTest(extra=extra[0]), self.assertRaises(RequirementSecurityError):
                    validate_pure_wheel(wheel)

    def test_rejects_platform_wheel(self):
        with tempfile.TemporaryDirectory() as directory:
            payload, _ = wheel_bytes("native", tag="cp311-cp311-android_24_arm64_v8a")
            wheel = Path(directory, "native-1.0-cp311-cp311-android_24_arm64_v8a.whl")
            wheel.write_bytes(payload)
            with self.assertRaises(RequirementSecurityError):
                validate_pure_wheel(wheel)

    def test_pypi_resolver_hash_and_transitive_dependency(self):
        root_payload, root_filename = wheel_bytes("root-pkg", requires=("dep-pkg>=1",))
        dep_payload, dep_filename = wheel_bytes("dep-pkg")
        projects = {}
        artifacts = {}
        for name, filename, payload in (
            ("root-pkg", root_filename, root_payload),
            ("dep-pkg", dep_filename, dep_payload),
        ):
            url = f"https://files.pythonhosted.org/packages/{filename}"
            projects[name] = {
                "releases": {"1.0": [{
                    "packagetype": "bdist_wheel", "filename": filename, "url": url,
                    "digests": {"sha256": hashlib.sha256(payload).hexdigest()},
                    "requires_python": ">=3.8",
                }]}
            }
            artifacts[url] = payload

        def opener(request, timeout=0):
            url = request.full_url
            if url.startswith("https://pypi.org/pypi/"):
                name = url.split("/")[-2]
                return FakeResponse(json.dumps(projects[name]).encode())
            return FakeResponse(artifacts[url])

        with tempfile.TemporaryDirectory() as directory:
            installer = PurePythonRequirementInstaller(directory, opener=opener)
            paths = installer.install_for_plugin("plugin", ["root-pkg>=1"])
            self.assertEqual(2, len(paths))
            self.assertTrue(all(path.is_dir() for path in paths))

    def test_hash_mismatch_rolls_back(self):
        payload, filename = wheel_bytes("hash-bad")
        url = f"https://files.pythonhosted.org/packages/{filename}"
        project = {"releases": {"1.0": [{
            "packagetype": "bdist_wheel", "filename": filename, "url": url,
            "digests": {"sha256": "0" * 64}, "requires_python": ">=3.8",
        }]}}
        def opener(request, timeout=0):
            return FakeResponse(json.dumps(project).encode()) if "pypi.org" in request.full_url else FakeResponse(payload)
        with tempfile.TemporaryDirectory() as directory:
            installer = PurePythonRequirementInstaller(directory, opener=opener)
            with self.assertRaises(RequirementSecurityError):
                installer.install_for_plugin("plugin", ["hash-bad"])
            self.assertFalse((Path(directory) / "requirements-manifest.json").exists())

    def test_sdist_only_is_rejected_without_build(self):
        project = {"releases": {"1.0": [{"packagetype": "sdist", "filename": "source.tar.gz", "url": "https://files.pythonhosted.org/source.tar.gz", "digests": {"sha256": "0" * 64}}]}}
        def opener(request, timeout=0): return FakeResponse(json.dumps(project).encode())
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RequirementSecurityError):
                PurePythonRequirementInstaller(directory, opener=opener).install_for_plugin("plugin", ["source-only"])

    def test_dependency_cycle_terminates(self):
        a, af = wheel_bytes("cycle-a", requires=("cycle-b>=1",))
        b, bf = wheel_bytes("cycle-b", requires=("cycle-a>=1",))
        with tempfile.TemporaryDirectory() as directory:
            paths = self.resolver_for(
                [("cycle-a", a, af), ("cycle-b", b, bf)], directory
            ).install_for_plugin("plugin", ["cycle-a"])
            self.assertEqual(2, len(paths))

    def test_requested_extra_installs_its_marked_dependency(self):
        root, root_filename = wheel_bytes(
            "extra-root", requires=('extra-dep>=1; extra == "feature"',)
        )
        dependency, dependency_filename = wheel_bytes("extra-dep")
        with tempfile.TemporaryDirectory() as directory:
            paths = self.resolver_for(
                [
                    ("extra-root", root, root_filename),
                    ("extra-dep", dependency, dependency_filename),
                ],
                directory,
            ).install_for_plugin("plugin", ["extra-root[feature]"])
            self.assertEqual(2, len(paths))

    def test_late_extra_activation_expands_already_resolved_package(self):
        root, root_filename = wheel_bytes(
            "late-root", requires=('late-dep; extra == "feature"',)
        )
        dependency, dependency_filename = wheel_bytes("late-dep")
        with tempfile.TemporaryDirectory() as directory:
            paths = self.resolver_for(
                [
                    ("late-root", root, root_filename),
                    ("late-dep", dependency, dependency_filename),
                ],
                directory,
            ).install_for_plugin(
                "plugin", ["late-root", "late-root[feature]"]
            )
            self.assertEqual(2, len(paths))

    def test_requested_extra_keeps_base_marker_dependencies(self):
        root, root_filename = wheel_bytes(
            "base-root", requires=('base-dep; extra != "feature"',)
        )
        dependency, dependency_filename = wheel_bytes("base-dep")
        with tempfile.TemporaryDirectory() as directory:
            paths = self.resolver_for(
                [
                    ("base-root", root, root_filename),
                    ("base-dep", dependency, dependency_filename),
                ],
                directory,
            ).install_for_plugin("plugin", ["base-root[feature]"])
            self.assertEqual(2, len(paths))

    def test_incompatible_transitive_constraints_abort(self):
        left, lf = wheel_bytes("left", requires=("shared<1",))
        right, rf = wheel_bytes("right", requires=("shared>=1",))
        shared, sf = wheel_bytes("shared")
        with tempfile.TemporaryDirectory() as directory:
            installer = self.resolver_for(
                [("left", left, lf), ("right", right, rf), ("shared", shared, sf)],
                directory,
            )
            with self.assertRaises(RequirementSecurityError):
                installer.install_for_plugin("plugin", ["left", "right"])

    def test_url_requirement_is_rejected_before_network(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RequirementSecurityError):
                PurePythonRequirementInstaller(directory).install_for_plugin(
                    "plugin", ["demo @ https://example.com/demo.whl"]
                )


if __name__ == "__main__":
    unittest.main()
