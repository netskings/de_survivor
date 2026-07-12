from plugin_runtime.metadata import read_metadata


def get_metadata(file_path):
    return read_metadata(file_path).to_dict()
