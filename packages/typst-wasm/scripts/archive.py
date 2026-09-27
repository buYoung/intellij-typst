"""Create a reproducible archive from a generated, flat engine distribution."""
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

source = Path(sys.argv[1])
output = Path(sys.argv[2])
with ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(source.iterdir()):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Unexpected archive input: {path}")
        info = ZipInfo(path.name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, path.read_bytes(), compresslevel=9)
