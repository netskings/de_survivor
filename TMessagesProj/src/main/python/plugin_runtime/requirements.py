"""Validation and atomic extraction for universal pure-Python wheels only."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from dataclasses import dataclass
from email.parser import BytesParser
from email.policy import default as email_policy
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen

from packaging.markers import default_environment
from packaging.requirements import InvalidRequirement, Requirement
from packaging.specifiers import InvalidSpecifier, SpecifierSet
from packaging.utils import canonicalize_name, parse_wheel_filename
from packaging.version import InvalidVersion, Version

from .errors import RequirementSecurityError

MAX_WHEEL_FILES = 20_000
MAX_WHEEL_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
MAX_PYPI_JSON_BYTES = 8 * 1024 * 1024
MAX_WHEEL_DOWNLOAD_BYTES = 64 * 1024 * 1024
PYPI_JSON_BASE = "https://pypi.org/pypi"
NATIVE_SUFFIXES = (
    ".so",
    ".pyd",
    ".dll",
    ".dylib",
    ".exe",
    ".jar",
    ".aar",
    ".dex",
)
WHEEL_NAME_RE = re.compile(
    r"^(?P<distribution>.+)-(?P<version>[^-]+)"
    r"(?:-(?P<build>[^-]+))?-(?P<python>[^-]+)-(?P<abi>[^-]+)-(?P<platform>[^-]+)\.whl$",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class ValidatedWheel:
    path: Path
    distribution: str
    version: str
    digest: str
    members: tuple[zipfile.ZipInfo, ...]

    @property
    def install_directory_name(self) -> str:
        distribution = re.sub(r"[^A-Za-z0-9_.-]", "_", self.distribution)
        version = re.sub(r"[^A-Za-z0-9_.-]", "_", self.version)
        return f"{distribution}-{version}-{self.digest[:12]}"


def _safe_member_name(name: str) -> PurePosixPath:
    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    if not normalized or normalized.startswith(("/", "\\")):
        raise RequirementSecurityError(f"unsafe absolute wheel member: {name!r}")
    if path.is_absolute() or ".." in path.parts or any(":" in part for part in path.parts):
        raise RequirementSecurityError(f"unsafe wheel member path: {name!r}")
    return path


def validate_pure_wheel(path: str | Path) -> ValidatedWheel:
    wheel_path = Path(path)
    match = WHEEL_NAME_RE.fullmatch(wheel_path.name)
    if not match:
        raise RequirementSecurityError("invalid PEP 427 wheel filename")
    if match.group("abi").lower() != "none" or match.group("platform").lower() != "any":
        raise RequirementSecurityError("only ABI-none, platform-any wheels are allowed")
    python_tags = match.group("python").lower().split(".")
    if not any(tag in {"py3", "py311"} for tag in python_tags):
        raise RequirementSecurityError("wheel does not declare Python 3.11 compatibility")

    try:
        archive = zipfile.ZipFile(wheel_path, "r")
    except (OSError, zipfile.BadZipFile) as exc:
        raise RequirementSecurityError(f"invalid wheel archive: {exc}") from exc
    with archive:
        members = archive.infolist()
        if len(members) > MAX_WHEEL_FILES:
            raise RequirementSecurityError("wheel contains too many files")
        total_size = sum(member.file_size for member in members)
        if total_size > MAX_WHEEL_UNCOMPRESSED_BYTES:
            raise RequirementSecurityError("wheel exceeds the uncompressed size limit")

        wheel_metadata: zipfile.ZipInfo | None = None
        seen_names: set[str] = set()
        for member in members:
            safe_path = _safe_member_name(member.filename)
            canonical_member = safe_path.as_posix().casefold()
            if canonical_member in seen_names:
                raise RequirementSecurityError("wheel contains duplicate member paths")
            seen_names.add(canonical_member)
            if member.flag_bits & 0x1:
                raise RequirementSecurityError("encrypted wheel members are not allowed")
            if (
                member.file_size > 1024 * 1024
                and member.compress_size > 0
                and member.file_size / member.compress_size > 200
            ):
                raise RequirementSecurityError("wheel contains a suspicious compression bomb")
            unix_mode = member.external_attr >> 16
            if stat.S_ISLNK(unix_mode):
                raise RequirementSecurityError("wheel symlinks are not allowed")
            lower_name = safe_path.name.lower()
            if lower_name == "setup.py":
                raise RequirementSecurityError("build scripts are forbidden in installed wheels")
            if lower_name.endswith(".pth"):
                raise RequirementSecurityError(".pth startup code/path injection is forbidden")
            if lower_name.endswith(NATIVE_SUFFIXES):
                raise RequirementSecurityError(
                    f"native or executable payload is forbidden: {member.filename}"
                )
            if (
                len(safe_path.parts) == 2
                and safe_path.parts[0].endswith(".dist-info")
                and safe_path.name == "WHEEL"
            ):
                wheel_metadata = member
        if wheel_metadata is None:
            raise RequirementSecurityError("wheel has no .dist-info/WHEEL metadata")
        try:
            metadata_text = archive.read(wheel_metadata).decode("utf-8")
        except (KeyError, UnicodeError) as exc:
            raise RequirementSecurityError("invalid WHEEL metadata") from exc
        fields: dict[str, list[str]] = {}
        for line in metadata_text.splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                fields.setdefault(key.strip().lower(), []).append(value.strip())
        if fields.get("root-is-purelib", [""])[0].lower() != "true":
            raise RequirementSecurityError("wheel is not marked Root-Is-Purelib: true")
        tags = fields.get("tag", [])
        if not tags or any(
            not tag.lower().endswith("-none-any")
            or not any(
                candidate in {"py3", "py311"}
                for candidate in tag.lower().split("-", 1)[0].split(".")
            )
            for tag in tags
        ):
            raise RequirementSecurityError("WHEEL metadata contains a non-universal tag")

    digest = hashlib.sha256(wheel_path.read_bytes()).hexdigest()
    return ValidatedWheel(
        path=wheel_path,
        distribution=match.group("distribution"),
        version=match.group("version"),
        digest=digest,
        members=tuple(members),
    )


def _install_relative_path(member_path: PurePosixPath) -> PurePosixPath | None:
    parts = member_path.parts
    if parts and parts[0].endswith(".data"):
        if len(parts) < 3:
            return None
        scheme = parts[1]
        if scheme == "purelib":
            return PurePosixPath(*parts[2:])
        # Scripts, headers and arbitrary data are not import dependencies.
        return None
    return member_path


def install_pure_wheel_atomic(
    wheel_path: str | Path, dependencies_root: str | Path
) -> Path:
    validated = validate_pure_wheel(wheel_path)
    root = Path(dependencies_root)
    root.mkdir(parents=True, exist_ok=True)
    destination = root / validated.install_directory_name
    if destination.is_dir():
        return destination
    staging = Path(tempfile.mkdtemp(prefix=".wheel-", dir=str(root)))
    try:
        with zipfile.ZipFile(validated.path, "r") as archive:
            for member in validated.members:
                source_path = _safe_member_name(member.filename)
                relative_path = _install_relative_path(source_path)
                if relative_path is None or not relative_path.parts:
                    continue
                target = staging.joinpath(*relative_path.parts)
                resolved = target.resolve()
                if staging.resolve() not in resolved.parents:
                    raise RequirementSecurityError("wheel extraction escaped staging directory")
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member, "r") as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
        marker = staging / ".telegram-plugin-wheel"
        marker.write_text(validated.digest + "\n", encoding="ascii")
        try:
            os.replace(staging, destination)
        except FileExistsError:
            if not destination.is_dir():
                raise
        return destination
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


@dataclass(frozen=True)
class WheelArtifact:
    name: str
    version: Version
    filename: str
    url: str
    sha256: str
    requires_python: str | None


@dataclass(frozen=True)
class ResolvedWheel:
    artifact: WheelArtifact
    path: Path
    requires_dist: tuple[str, ...]


def _atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            json.dump(value, output, ensure_ascii=False, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def _read_limited(response, maximum: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = response.read(min(64 * 1024, maximum + 1 - total))
        if not chunk:
            break
        total += len(chunk)
        if total > maximum:
            raise RequirementSecurityError("download exceeds the configured size limit")
        chunks.append(chunk)
    return b"".join(chunks)


def _open(opener: Callable, url: str, maximum: int) -> bytes:
    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise RequirementSecurityError("requirements may only be downloaded over HTTPS")
    host = (parsed.hostname or "").lower()
    if host != "pypi.org" and not host.endswith(".pythonhosted.org"):
        raise RequirementSecurityError(f"untrusted requirement host: {host!r}")
    request = Request(url, headers={"Accept": "application/json", "User-Agent": "Telegram-Python-Plugins/1"})
    response = opener(request, timeout=30)
    if hasattr(response, "__enter__"):
        with response as stream:
            return _read_limited(stream, maximum)
    try:
        return _read_limited(response, maximum)
    finally:
        close = getattr(response, "close", None)
        if close:
            close()


def _parse_requirement(value: str) -> Requirement:
    if not isinstance(value, str) or not value.strip():
        raise RequirementSecurityError("requirements must be non-empty PEP 508 strings")
    try:
        requirement = Requirement(value)
    except InvalidRequirement as exc:
        raise RequirementSecurityError(f"invalid requirement {value!r}: {exc}") from exc
    if requirement.url is not None:
        raise RequirementSecurityError("URL, VCS and local-path requirements are forbidden")
    return requirement


def _marker_applies(
    requirement: Requirement, active_extras: Iterable[str] = ()
) -> bool:
    if requirement.marker is None:
        return True
    # Extras add optional dependencies to the distribution's base dependency
    # set. Evaluate the empty/base context as well as every requested extra,
    # matching the way installers evaluate Requires-Dist markers.
    extras = ("", *sorted(set(active_extras)))
    for extra in extras:
        environment = default_environment()
        environment.update(
            {
                "python_version": "3.11",
                "python_full_version": "3.11.14",
                "implementation_name": "cpython",
                "platform_system": "Android",
                "sys_platform": "android",
                "os_name": "posix",
                "extra": extra,
            }
        )
        if requirement.marker.evaluate(environment):
            return True
    return False


def _compatible_filename(filename: str, expected_name: str, expected_version: Version) -> bool:
    if not filename.lower().endswith(".whl"):
        return False
    try:
        distribution, version, _build, tags = parse_wheel_filename(filename)
    except (ValueError, InvalidVersion):
        return False
    if canonicalize_name(distribution) != expected_name or version != expected_version:
        return False
    return any(
        tag.abi == "none"
        and tag.platform == "any"
        and tag.interpreter in {"py3", "py311"}
        for tag in tags
    )


def _python_constraint_allows(value: str | None) -> bool:
    if not value:
        return True
    try:
        return Version("3.11.14") in SpecifierSet(value)
    except (InvalidSpecifier, InvalidVersion) as exc:
        raise RequirementSecurityError(f"invalid Requires-Python value {value!r}") from exc


def _load_project_json(name: str, opener: Callable, json_base: str) -> dict[str, Any]:
    url = f"{json_base.rstrip('/')}/{quote(name)}/json"
    try:
        payload = json.loads(_open(opener, url, MAX_PYPI_JSON_BYTES).decode("utf-8"))
    except (UnicodeError, ValueError, OSError) as exc:
        raise RequirementSecurityError(f"cannot read PyPI metadata for {name}: {exc}") from exc
    if not isinstance(payload, dict) or not isinstance(payload.get("releases"), dict):
        raise RequirementSecurityError(f"invalid PyPI response for {name}")
    return payload


def _select_artifact(
    canonical_name: str,
    constraints: Iterable[SpecifierSet],
    opener: Callable,
    json_base: str,
) -> WheelArtifact:
    project = _load_project_json(canonical_name, opener, json_base)
    versions: list[Version] = []
    for raw_version in project["releases"]:
        try:
            version = Version(raw_version)
        except InvalidVersion:
            continue
        if version.is_prerelease or version.is_devrelease:
            continue
        if all(version in constraint for constraint in constraints):
            versions.append(version)
    for version in sorted(versions, reverse=True):
        files = project["releases"].get(str(version), [])
        candidates: list[WheelArtifact] = []
        for item in files if isinstance(files, list) else []:
            if not isinstance(item, dict) or item.get("packagetype") != "bdist_wheel":
                continue
            filename = item.get("filename")
            url = item.get("url")
            digest = (item.get("digests") or {}).get("sha256")
            requires_python = item.get("requires_python")
            if not all(isinstance(value, str) and value for value in (filename, url, digest)):
                continue
            if not _compatible_filename(filename, canonical_name, version):
                continue
            if not _python_constraint_allows(requires_python):
                continue
            candidates.append(
                WheelArtifact(canonical_name, version, filename, url, digest.lower(), requires_python)
            )
        if candidates:
            # Prefer the broad py3 tag over a Python-minor-specific pure wheel.
            candidates.sort(key=lambda item: ("-py3-none-any.whl" not in item.filename.lower(), item.filename))
            return candidates[0]
    joined = ",".join(str(value) for value in constraints) or "(any)"
    raise RequirementSecurityError(
        f"no Python 3.11 universal pure wheel for {canonical_name} satisfying {joined}"
    )


def _download_artifact(artifact: WheelArtifact, cache_dir: Path, opener: Callable) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    destination = cache_dir / artifact.filename
    if destination.is_file():
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        if digest == artifact.sha256:
            return destination
        destination.unlink()
    payload = _open(opener, artifact.url, MAX_WHEEL_DOWNLOAD_BYTES)
    digest = hashlib.sha256(payload).hexdigest()
    if digest != artifact.sha256:
        raise RequirementSecurityError(
            f"SHA-256 mismatch for {artifact.filename}: expected {artifact.sha256}, got {digest}"
        )
    descriptor, temporary = tempfile.mkstemp(prefix=".wheel-", suffix=".tmp", dir=str(cache_dir))
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise
    return destination


def _read_core_metadata(wheel_path: Path, artifact: WheelArtifact) -> tuple[str, ...]:
    validated = validate_pure_wheel(wheel_path)
    if canonicalize_name(validated.distribution) != artifact.name or Version(validated.version) != artifact.version:
        raise RequirementSecurityError("wheel filename metadata does not match the selected release")
    with zipfile.ZipFile(wheel_path, "r") as archive:
        metadata_members = [
            member for member in archive.infolist()
            if len(PurePosixPath(member.filename).parts) == 2
            and PurePosixPath(member.filename).parts[0].endswith(".dist-info")
            and PurePosixPath(member.filename).name == "METADATA"
        ]
        if len(metadata_members) != 1:
            raise RequirementSecurityError("wheel must contain exactly one .dist-info/METADATA")
        message = BytesParser(policy=email_policy).parsebytes(archive.read(metadata_members[0]))
    metadata_name = message.get("Name")
    metadata_version = message.get("Version")
    if not metadata_name or canonicalize_name(metadata_name) != artifact.name:
        raise RequirementSecurityError("METADATA Name does not match wheel filename")
    try:
        if not metadata_version or Version(metadata_version) != artifact.version:
            raise RequirementSecurityError("METADATA Version does not match wheel filename")
    except InvalidVersion as exc:
        raise RequirementSecurityError("invalid METADATA Version") from exc
    if not _python_constraint_allows(message.get("Requires-Python")):
        raise RequirementSecurityError("wheel METADATA excludes Python 3.11")
    requirements = tuple(message.get_all("Requires-Dist", []))
    for requirement in requirements:
        _parse_requirement(requirement)
    return requirements


class PurePythonRequirementInstaller:
    """Small deterministic PyPI installer which never invokes a build backend."""

    def __init__(
        self,
        dependencies_root: str | Path,
        *,
        opener: Callable = urlopen,
        json_base: str = PYPI_JSON_BASE,
    ) -> None:
        self.root = Path(dependencies_root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.cache = self.root / ".wheel-cache"
        self.manifest_path = self.root / "requirements-manifest.json"
        self.opener = opener
        self.json_base = json_base

    def _load_manifest(self) -> dict[str, Any]:
        if not self.manifest_path.exists():
            return {"plugins": {}, "packages": {}}
        try:
            value = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise RequirementSecurityError("dependency manifest is corrupt")
        if not isinstance(value, dict):
            raise RequirementSecurityError("dependency manifest is invalid")
        return {"plugins": dict(value.get("plugins", {})), "packages": dict(value.get("packages", {}))}

    def install_for_plugin(self, plugin_id: str, requirements: Iterable[str]) -> list[Path]:
        parsed_roots = [_parse_requirement(item) for item in requirements]
        manifest = self._load_manifest()
        plugins = manifest["plugins"]
        serialized_roots = [str(item) for item in parsed_roots]
        if plugins.get(plugin_id) == serialized_roots:
            active = self.active_paths()
            expected_count = len(manifest.get("packages", {}))
            if len(active) == expected_count:
                return active
        plugins[plugin_id] = serialized_roots

        constraints: dict[str, list[SpecifierSet]] = {}
        requested_extras: dict[str, set[str]] = {}
        expanded_extras: dict[str, frozenset[str]] = {}
        pending: list[tuple[Requirement, frozenset[str]]] = []
        for values in plugins.values():
            if not isinstance(values, list):
                raise RequirementSecurityError("dependency manifest contains invalid plugin requirements")
            pending.extend(
                (_parse_requirement(item), frozenset()) for item in values
            )

        resolved: dict[str, ResolvedWheel] = {}
        while pending:
            requirement, marker_extras = pending.pop(0)
            if not _marker_applies(requirement, marker_extras):
                continue
            name = canonicalize_name(requirement.name)
            constraints.setdefault(name, []).append(requirement.specifier)
            active_extras = requested_extras.setdefault(name, set())
            extras_changed = not requirement.extras.issubset(active_extras)
            active_extras.update(requirement.extras)
            existing = resolved.get(name)
            if existing is not None:
                if not all(existing.artifact.version in item for item in constraints[name]):
                    raise RequirementSecurityError(
                        f"conflicting constraints for {name}: "
                        + ", ".join(str(item) for item in constraints[name])
                    )
            else:
                artifact = _select_artifact(name, constraints[name], self.opener, self.json_base)
                wheel = _download_artifact(artifact, self.cache, self.opener)
                requires_dist = _read_core_metadata(wheel, artifact)
                existing = ResolvedWheel(artifact, wheel, requires_dist)
                resolved[name] = existing

            extras_snapshot = frozenset(active_extras)
            if name not in expanded_extras or extras_changed:
                expanded_extras[name] = extras_snapshot
                pending.extend(
                    (_parse_requirement(item), extras_snapshot)
                    for item in existing.requires_dist
                )

        # A late transitive constraint may invalidate an earlier greedy choice.
        for name, selected in resolved.items():
            if not all(selected.artifact.version in item for item in constraints.get(name, ())):
                raise RequirementSecurityError(f"dependency conflict for {name}")

        transaction = Path(tempfile.mkdtemp(prefix=".requirements-", dir=str(self.root)))
        published: list[Path] = []
        try:
            staged: list[Path] = []
            for selected in resolved.values():
                staged.append(install_pure_wheel_atomic(selected.path, transaction))
            for directory in staged:
                destination = self.root / directory.name
                if not destination.exists():
                    os.replace(directory, destination)
                    published.append(destination)
            package_manifest = {
                name: {
                    "version": str(value.artifact.version),
                    "directory": validate_pure_wheel(value.path).install_directory_name,
                    "sha256": value.artifact.sha256,
                    "requires_dist": list(value.requires_dist),
                }
                for name, value in sorted(resolved.items())
            }
            _atomic_json(self.manifest_path, {"plugins": plugins, "packages": package_manifest})
        except BaseException:
            for directory in published:
                shutil.rmtree(directory, ignore_errors=True)
            raise
        finally:
            shutil.rmtree(transaction, ignore_errors=True)
        return [self.root / value["directory"] for value in package_manifest.values()]

    def active_paths(self) -> list[Path]:
        manifest = self._load_manifest()
        result = []
        for value in manifest["packages"].values():
            if isinstance(value, dict) and isinstance(value.get("directory"), str):
                path = self.root / value["directory"]
                if path.is_dir():
                    result.append(path)
        return result

    def unregister_plugin(self, plugin_id: str) -> None:
        manifest = self._load_manifest()
        manifest["plugins"].pop(plugin_id, None)
        # Package cleanup is intentionally conservative while a single process
        # may still have modules in sys.modules. The next resolution rewrites
        # the active package set and stale directories are never added to path.
        _atomic_json(self.manifest_path, manifest)
