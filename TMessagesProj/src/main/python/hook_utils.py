"""Failure-tolerant Java reflection helpers from the stable SDK."""

try:
    from java.lang import Class as JavaClass
    from java.lang import Object as JavaObject
except (ImportError, ModuleNotFoundError):
    # Keep the public aliases importable in host-side contract tests. Android
    # replaces them with Chaquopy's java.lang types.
    JavaClass = type
    JavaObject = object


def _find_field(clazz, field_name):
    while clazz is not None:
        try:
            field = clazz.getDeclaredField(field_name)
            field.setAccessible(True)
            return field
        except BaseException:
            try:
                clazz = clazz.getSuperclass()
            except BaseException:
                return None
    return None


def find_class(class_name: str):
    try:
        from java.lang import Class
        return Class.forName(class_name)
    except BaseException:
        return None


def get_private_field(obj, field_name: str):
    try:
        field = _find_field(obj.getClass(), field_name)
        return None if field is None else field.get(obj)
    except BaseException:
        return None


def set_private_field(obj, field_name: str, new_value):
    try:
        field = _find_field(obj.getClass(), field_name)
        if field is None:
            return False
        field.set(obj, new_value)
        return True
    except BaseException:
        return False


def get_static_private_field(clazz, field_name: str):
    try:
        field = _find_field(clazz, field_name)
        return None if field is None else field.get(None)
    except BaseException:
        return None


def set_static_private_field(clazz, field_name: str, new_value):
    try:
        field = _find_field(clazz, field_name)
        if field is None:
            return False
        field.set(None, new_value)
        return True
    except BaseException:
        return False


__all__ = [
    "JavaClass",
    "JavaObject",
    "find_class",
    "get_private_field",
    "get_static_private_field",
    "set_private_field",
    "set_static_private_field",
]
