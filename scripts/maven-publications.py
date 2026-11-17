"""当前源码的完整 Maven publication 集合；版本只校验格式。"""
import argparse
import re


def modules(version):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[.-][A-Za-z0-9.-]+)?", version)
    if match is None:
        raise ValueError("Invalid Maven version")
    return ["live-core", "live-kuikly", "live-room-core"]


def publications(version):
    suffixes = ["", "-android", "-iosarm64", "-iosx64", "-iossimulatorarm64", "-ohosarm64"]
    result = [name + suffix for name in modules(version) for suffix in suffixes]
    result.append("live-room-core-jvm")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--modules", action="store_true")
    args = parser.parse_args()
    print(",".join(modules(args.version) if args.modules else publications(args.version)))
