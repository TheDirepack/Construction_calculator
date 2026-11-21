"""Optional conversion entrypoint. Run only after loading trained weights.

Examples:
  python scripts_export_models.py --checkpoint detector.pt --model detector --format coreml
  python scripts_export_models.py --checkpoint refiner.pt --model refiner --format litert

Conversion runtimes are intentionally optional and no NPU execution is implied.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import torch
from blueprint_symbols.models import SymbolDetector, AnchorRefiner


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--checkpoint', required=True, type=Path)
    parser.add_argument('--model', required=True, choices=['detector','refiner'])
    parser.add_argument('--format', required=True, choices=['coreml','litert'])
    parser.add_argument('--classes', type=int, default=16)
    parser.add_argument('--anchors', type=int, default=4)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    model = SymbolDetector(args.classes) if args.model=='detector' else AnchorRefiner(args.anchors)
    state = torch.load(args.checkpoint, map_location='cpu', weights_only=True)
    model.load_state_dict(state)
    model.eval()
    shape = (1, 3, 512, 512) if args.model=='detector' else (1,3,160,160)
    example = torch.zeros(shape)
    with torch.no_grad():
        expected = model(example)
    output = args.output or Path(f'{args.model}.{ "mlpackage" if args.format=="coreml" else "tflite"}')
    if args.format=='coreml':
        import coremltools as ct
        traced = torch.jit.trace(model, example)
        converted = ct.convert(traced, inputs=[ct.TensorType(name='image', shape=shape)],
                               convert_to='mlprogram', minimum_deployment_target=ct.target.iOS16)
        converted.save(str(output))
    else:
        import litert_torch
        edge_model = litert_torch.convert(model, (example,))
        actual = edge_model(example)
        actual_list = actual if isinstance(actual, (tuple,list)) else [actual]
        expected_list = expected if isinstance(expected, (tuple,list)) else [expected]
        if len(actual_list) != len(expected_list):
            raise RuntimeError('Conversion changed the output count')
        for x,y in zip(expected_list, actual_list):
            if not torch.allclose(x.detach(), torch.as_tensor(y), rtol=1e-3, atol=1e-3):
                raise RuntimeError('Converted LiteRT output differs from PyTorch')
        edge_model.export(str(output))
    print(f'Created {output} (graph conversion only; physical NPU execution unverified)')

if __name__ == '__main__':
    main()
