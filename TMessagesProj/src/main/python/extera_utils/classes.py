"""Stable class-proxy symbol surface with an explicit phase-two runtime gate."""

from __future__ import annotations

from plugin_runtime.errors import reflection_phase_error

_MISSING = object()


def _unsupported(feature: str):
    raise reflection_phase_error(feature)


class _MethodMetadata:
    def __init__(self, kind, return_type, arg_types, modifiers, throws=None, java_name=None, implementation: str = "python", code=None, arg_names=None) -> None:
        self.kind = kind
        self.return_type = return_type
        self.arg_types = arg_types
        self.modifiers = modifiers
        self.throws = throws
        self.java_name = java_name
        self.implementation = implementation
        self.code = code
        self.arg_names = arg_names

    def resolve(self, fn, python_name):
        _unsupported("extera_utils.classes method proxy metadata")


class _ResolvedMethodMetadata(_MethodMetadata):
    def __init__(self, *, kind, python_name, java_name, return_type, arg_types, modifiers, throws=None, implementation: str = "python", code=None, arg_names=None) -> None:
        super().__init__(kind, return_type, arg_types, modifiers, throws, java_name, implementation, code, arg_names)
        self.python_name = python_name


class _ConstructorMetadata:
    def __init__(self, arg_types=None, *, phase: str = "post") -> None:
        self.arg_types = arg_types
        self.phase = phase

    def resolve(self, fn, python_name):
        _unsupported("extera_utils.classes constructor proxy metadata")


class _ResolvedConstructorMetadata(_ConstructorMetadata):
    def __init__(self, *, python_name, arg_types, phase) -> None:
        super().__init__(arg_types, phase=phase)
        self.python_name = python_name


class _ClassBuildMetadata:
    def resolve(self, python_name):
        _unsupported("extera_utils.classes class builder")


class _ResolvedClassBuildMetadata:
    def __init__(self, *, python_name) -> None:
        self.python_name = python_name


class _FieldMetadata:
    def __init__(self, field_type, default=_MISSING, modifiers: str = "public", methods=None) -> None:
        self.field_type = field_type
        self.default = default
        self.modifiers = modifiers
        self.methods = methods

    def resolve(self, python_name):
        _unsupported("extera_utils.classes Java field")


class _ResolvedFieldMetadata(_FieldMetadata):
    def __init__(self, *, python_name, field_type, default, modifiers, methods) -> None:
        super().__init__(field_type, default, modifiers, methods)
        self.python_name = python_name


class _FieldAccessorMetadata:
    def __init__(self, kind, name=None, modifiers: str = "public") -> None:
        self.kind = kind
        self.name = name
        self.modifiers = modifiers

    def resolve(self, field_name, field_type):
        _unsupported("extera_utils.classes Java field accessor")


class _ResolvedFieldAccessorMetadata(_FieldAccessorMetadata):
    def __init__(self, *, kind, name, modifiers) -> None:
        super().__init__(kind, name, modifiers)


class JField:
    def __init__(self, field_type, *, default=_MISSING, modifiers: str = "public", methods=None) -> None:
        _unsupported("extera_utils.classes.JField")


class JMvelMethod:
    def __init__(self, metadata) -> None:
        _unsupported("extera_utils.classes.JMvelMethod")


class ClassInterceptor:
    def __init__(self, fn=None, pyclass=None, **kwargs) -> None:
        self.fn = fn
        self.pyclass = pyclass
        self.kwargs = kwargs

    def run(self, instance, method_name, args):
        _unsupported("extera_utils.classes.ClassInterceptor.run")


def class_proxy(clazz, *, fn: callable | None = None, interceptor=None, methods=None, constructors=None, custom_name=None, **kwargs):
    _unsupported("extera_utils.classes.class_proxy")


def _decorator_gate(feature: str):
    def decorator(fn_or_class):
        _unsupported(feature)

    return decorator


class JHelper:
    @staticmethod
    def Override(*signature, arg_types=None, modifiers: str = "public", throws=None, name=None):
        return _decorator_gate("extera_utils.classes.JHelper.Override")

    @staticmethod
    def Overload(name, arg_types, *, modifiers: str = "public", throws=None):
        return _decorator_gate("extera_utils.classes.JHelper.Overload")

    @staticmethod
    def Method(*signature, return_type=None, arg_types=None, modifiers: str = "public", throws=None, name=None):
        return _decorator_gate("extera_utils.classes.JHelper.Method")

    @staticmethod
    def MVELMethod(*, code, return_type=None, arguments=None, arg_types=None, modifiers: str = "public", throws=None, name=None, override: bool = False):
        return _decorator_gate("extera_utils.classes.JHelper.MVELMethod")

    @staticmethod
    def MVELOverride(*signature, code, arguments=None, arg_types=None, modifiers: str = "public", throws=None, name=None):
        return _decorator_gate("extera_utils.classes.JHelper.MVELOverride")

    @staticmethod
    def Constructor(*signature, arg_types=None):
        return _decorator_gate("extera_utils.classes.JHelper.Constructor")

    @staticmethod
    def PreConstructor(*signature, arg_types=None):
        return _decorator_gate("extera_utils.classes.JHelper.PreConstructor")

    @staticmethod
    def Field(field_type, *, default=_MISSING, modifiers: str = "public", methods=None):
        _unsupported("extera_utils.classes.JHelper.Field")

    @staticmethod
    def GetMethod(name=None, *, modifiers: str = "public"):
        return _decorator_gate("extera_utils.classes.JHelper.GetMethod")

    @staticmethod
    def SetMethod(name=None, *, modifiers: str = "public"):
        return _decorator_gate("extera_utils.classes.JHelper.SetMethod")

    @staticmethod
    def ClassBuilder():
        return _decorator_gate("extera_utils.classes.JHelper.ClassBuilder")


class Base:
    """Import-compatible base. Java materialization methods remain gated."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__()

    @classmethod
    def on_pre_init(cls, *args) -> None:
        return None

    def on_post_init(self, *args) -> None:
        return None

    @property
    def java(self):
        _unsupported("extera_utils.classes.Base.java")

    @property
    def this(self):
        _unsupported("extera_utils.classes.Base.this")

    @classmethod
    def extends(cls, java_class, *interfaces, methods=None, constructors=None, custom_name=None):
        _unsupported("extera_utils.classes.Base.extends")

    @classmethod
    def bind(cls, java_class, *interfaces, methods=None, constructors=None, custom_name=None):
        _unsupported("extera_utils.classes.Base.bind")

    @classmethod
    def java_class(cls):
        _unsupported("extera_utils.classes.Base.java_class")

    @classmethod
    def from_java(cls, instance, *, init_args=None):
        _unsupported("extera_utils.classes.Base.from_java")

    @classmethod
    def new_java_instance(cls, *args):
        _unsupported("extera_utils.classes.Base.new_java_instance")

    @classmethod
    def new_instance(cls, *args, init_args=None):
        _unsupported("extera_utils.classes.Base.new_instance")


joverride = JHelper.Override
joverload = JHelper.Overload
jmethod = JHelper.Method
jfield = JHelper.Field
jconstructor = JHelper.Constructor
jpreconstructor = JHelper.PreConstructor
jgetmethod = JHelper.GetMethod
jsetmethod = JHelper.SetMethod
jmvelmethod = JHelper.MVELMethod
jmveloverride = JHelper.MVELOverride
jMVELmethod = JHelper.MVELMethod
jMVELoverride = JHelper.MVELOverride
jclassbuilder = JHelper.ClassBuilder
java_subclass = class_proxy


class PyObj(Base):
    @classmethod
    def create(cls, obj):
        _unsupported("extera_utils.classes.PyObj.create")


__all__ = [
    "Base", "ClassInterceptor", "JField", "JHelper", "JMvelMethod", "PyObj",
    "class_proxy", "java_subclass", "jclassbuilder", "jconstructor", "jfield",
    "jgetmethod", "jmethod", "jmvelmethod", "jmveloverride", "jMVELmethod",
    "jMVELoverride", "joverload", "joverride", "jpreconstructor", "jsetmethod",
]
