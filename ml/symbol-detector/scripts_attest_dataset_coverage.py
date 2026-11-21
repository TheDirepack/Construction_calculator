"""Prepare separate, completeness-attested source labels for YOLO.

Existing CAD/SVG/COCO annotation imports identify *known* objects, but
training a joint YOLO detector also requires knowing which symbol families
are exhaustively labeled on each source drawing. This command asserts
that a human/source-dataset audit establishes complete labeling for the
listed classes. It never fabricates missing labels or edits original JSONs.

python scripts_attest_dataset_coverage.py 'converted/**/*.json' \\
 --families receptacle,switch,hvac_diffuser --output complete_labels/ --assert-exhaustive

Use only on sources verified exhaustive for EACH listed symbol family.
Unverified sources require additional labeling or an exploratory export with
explicit warnings, not silent negative training labels.
"""
from __future__ import annotations
import argparse
import glob
import hashlib
import json
from pathlib import Path


def attest(inputs: list[str], output:Path, families: list[str], *, verified: bool=False)->dict:
    if not verified:
        raise ValueError('--assert-exhaustive is required; completeness is not inferred from nonempty labels')
    requested={str(x).strip() for x in families if str(x).strip()}
    if not requested:
        raise ValueError('at least one target symbol family is required')
    if output.exists() and any(output.iterdir()):
        raise FileExistsError('refusing to overwrite existing output directory')
    source_files=set()
    for item in inputs:
        source_files.update(Path(x).resolve() for x in glob.glob(item,recursive=True) if x.endswith('.json'))
    selected=[]
    for path in sorted(source_files):
        if path.name.endswith('.report.json'):
            continue
        try: doc=json.loads(path.read_text())
        except (OSError,ValueError) as e:
            raise ValueError(f'invalid JSON: {path}') from e
        if not isinstance(doc,dict) or not isinstance(doc.get('instances'),list) or 'image_path' not in doc:
            continue
        if not doc.get('source_group_verified',False):
            raise ValueError(f'unverified source project group: {path}')
        image=(path.parent/doc['image_path']).resolve()
        if not image.is_file():
            raise FileNotFoundError(image)
        observed={i.get('symbol_family') for i in doc['instances']
                  if i.get('symbol_family') not in (None,'__unmapped__','ignore','unknown')}
        if observed-requested:
            raise ValueError(f'{path} contains mapped classes not in the attestation: '
                             f'{sorted(observed-requested)}; explicitly include or remap them')
        selected.append((path,image,doc))
    if not selected:
        raise ValueError('no normalized annotation records discovered')
    output.mkdir(parents=True,exist_ok=True)
    for path,image,doc in selected:
        new=dict(doc)
        new['image_path']=str(image)
        new['exhaustive_families']=sorted(set(new.get('exhaustive_families',[]))|requested)
        new['annotation_completeness_attestation']={
            'method':'dataset_or_source_coverage_explicitly_verified_by_user',
            'original_annotation':str(path),'scope':sorted(requested),
            'warning':'This flag is not automated visual verification.'}
        token=hashlib.sha256(str(path).encode()).hexdigest()[:12]
        dest=output/f'{path.stem}_{token}.json'
        dest.write_text(json.dumps(new,indent=2))
    return {'records':len(selected),'families':sorted(requested),
            'output':str(output.resolve()),'training_performed':False}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('annotations',nargs='+',help='source annotation JSON globs')
    p.add_argument('--families',required=True,help='comma-separated canonical symbol families')
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--assert-exhaustive',action='store_true')
    args=p.parse_args()
    print(json.dumps(attest(args.annotations,args.output,args.families.split(','),
                            verified=args.assert_exhaustive),indent=2))

if __name__=='__main__': main()
