"""按不可变 Maven 版本选择完整 publication 集合，保留历史发布合同。"""
import argparse
import re


def modules(version):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:[.-][A-Za-z0-9.-]+)?", version)
    if match is None:
        raise ValueError("Invalid Maven version")
    result = ["live-core", "live-kuikly"]
    if tuple(map(int, match.group(1, 2, 3))) >= (1, 1, 3):
        result.append("live-room-core")
    return result


def publications(version):
    suffixes = ["", "-android", "-iosarm64", "-iosx64", "-iossimulatorarm64", "-ohosarm64"]
    result = [name + suffix for name in modules(version) for suffix in suffixes]
    if "live-room-core" in modules(version):
        result.append("live-room-core-jvm")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--modules", action="store_true")
    args = parser.parse_args()
    print(",".join(modules(args.version) if args.modules else publications(args.version)))
