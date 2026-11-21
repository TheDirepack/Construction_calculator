"""Write an inventory of the downloaded blueprint/component library.

Produces blueprints_and_libraries/INVENTORY.json with per-directory file counts
and byte totals, plus a top-level total. Run after any download batch.
"""
import os
import json

ROOT = '/home/max/.t3/projects/construction-calculator/blueprints_and_libraries'


def dir_stats(path):
    files = 0
    size = 0
    for base, _dirs, names in os.walk(path):
        for name in names:
            full = os.path.join(base, name)
            files += 1
            try:
                size += os.path.getsize(full)
            except OSError:
                pass
    return files, size


def main():
    entries = []
    total_files = 0
    total_size = 0
    for name in sorted(os.listdir(ROOT)):
        path = os.path.join(ROOT, name)
        if not os.path.isdir(path):
            continue
        files, size = dir_stats(path)
        children = []
        for child in sorted(os.listdir(path)):
            cpath = os.path.join(path, child)
            if os.path.isdir(cpath):
                cfiles, csize = dir_stats(cpath)
                children.append({'name': child, 'files': cfiles, 'bytes': csize})
        entries.append({'name': name, 'files': files, 'bytes': size, 'children': children})
        total_files += files
        total_size += size
    data = {'root': ROOT, 'total_files': total_files, 'total_bytes': total_size,
            'total_mb': round(total_size / 1024 / 1024, 1), 'directories': entries}
    with open(os.path.join(ROOT, 'INVENTORY.json'), 'w') as f:
        json.dump(data, f, indent=1)
    print(f'{total_files} files, {total_size/1024/1024/1024:.2f} GB')
    for e in entries:
        print(f"{e['name']:32s} {e['files']:6d} files {e['bytes']/1024/1024:9.1f} MB")
        for c in e['children']:
            print(f"    {c['name']:28s} {c['files']:6d} files {c['bytes']/1024/1024:9.1f} MB")


if __name__ == '__main__':
    main()
