"""Inventory CAD symbol inserts with zero image labeling or supplied class map.

python scripts_inventory_dxf.py 'cad/**/*.dxf' --out cad_blocks.json
Reports block counts by source drawing and symbol geometry metadata. No
semantic classification is claimed from arbitrary CAD block names.
"""
from __future__ import annotations
from pathlib import Path
from collections import Counter
import glob
import argparse
import json


def inventory(paths: list[Path]) -> dict:
    try:
        import ezdxf
    except ImportError as ex:
        raise RuntimeError('ezdxf is needed: pip install ezdxf') from ex
    total=Counter(); by_file={}; errors={}
    for path in paths:
        try:
            doc=ezdxf.readfile(str(path))
            local=Counter()
            for e in doc.modelspace():
                if e.dxftype()=='INSERT':
                    local[e.dxf.name]+=e.mcount if e.mcount>1 else 1
            total.update(local)
            by_file[str(path)]=dict(local.most_common())
        except Exception as ex:
            errors[str(path)]=type(ex).__name__+': '+str(ex)
    return {'files_scanned':len(paths),'files_with_block_inserts':sum(bool(x) for x in by_file.values()),
            'blocks':dict(total.most_common()),'by_file':by_file,'errors':errors,
            'note':'Block names are unverified identifiers. The DXF label generator requires explicit class mapping and exact CAD-to-raster transform.'}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('cad_glob')
    p.add_argument('--out',required=True,type=Path)
    args=p.parse_args()
    paths=[Path(s) for s in glob.glob(args.cad_glob,recursive=True)]
    if not paths:
        p.error('no DXF files matched')
    result=inventory(paths)
    args.out.write_text(json.dumps(result,indent=2))
    print('DXF files:',len(paths),'distinct block names:',len(result['blocks']))
    if result['errors']:
        print('WARNING: errors:',len(result['errors']))


if __name__=='__main__':
    main()
