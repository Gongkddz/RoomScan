"""Archive a portable folder once, including dot directories and license files."""
from pathlib import Path
import sys
from zipfile import ZipFile, ZIP_DEFLATED

bundle, destination = map(Path, sys.argv[1:])
with ZipFile(destination, 'w', ZIP_DEFLATED, compresslevel=6) as archive:
    for file in sorted(bundle.rglob('*')):
        if not file.is_file():
            continue
        relative = file.relative_to(bundle)
        if '.runtime' in relative.parts or file.suffix.lower() in {'.jks', '.keystore', '.key', '.pem'}:
            raise ValueError('Private runtime file in release bundle: ' + str(relative))
        archive.write(file, relative.as_posix())
