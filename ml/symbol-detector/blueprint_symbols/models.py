"""Untrained, accelerator-friendly blueprint symbol model prototypes.

Only standard convolutions, batch normalization, ReLU, nearest-neighbor
upsampling, concatenation, and raw tensor outputs appear in the graphs.
Post-processing belongs outside the neural graphs.
"""
from __future__ import annotations

import torch
from torch import nn
import torch.nn.functional as F


class ConvBNAct(nn.Sequential):
    def __init__(self, in_channels: int, out_channels: int, kernel: int = 3, stride: int = 1):
        super().__init__(
            nn.Conv2d(in_channels, out_channels, kernel, stride=stride, padding=kernel // 2, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.ReLU(inplace=False),
        )


class DWBlock(nn.Module):
    def __init__(self, in_channels: int, out_channels: int, stride: int = 1):
        super().__init__()
        self.path = nn.Sequential(
            nn.Conv2d(in_channels, in_channels, 3, stride=stride, padding=1, groups=in_channels, bias=False),
            nn.BatchNorm2d(in_channels), nn.ReLU(inplace=False),
            nn.Conv2d(in_channels, out_channels, 1, bias=False),
            nn.BatchNorm2d(out_channels), nn.ReLU(inplace=False),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.path(x)


class TinyBackbone(nn.Module):
    """Stride-4, -8, -16 multi-scale features; depthwise CNN, no attention."""

    def __init__(self):
        super().__init__()
        self.stem = ConvBNAct(3, 24, 3, 2)
        self.stage2 = nn.Sequential(DWBlock(24, 32, 2), DWBlock(32, 32))
        self.stage3 = nn.Sequential(DWBlock(32, 56, 2), DWBlock(56, 56))
        self.stage4 = nn.Sequential(DWBlock(56, 88, 2), DWBlock(88, 88))

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        x = self.stem(x)
        p2 = self.stage2(x)
        p3 = self.stage3(p2)
        p4 = self.stage4(p3)
        return p2, p3, p4


class SymbolDetector(nn.Module):
    """P2-P4 anchor-free detector intended for small glyphs.

    Output per scale has 1 objectness logit + K class logits + 4 box values
    (dx, dy, log-width, log-height). Output decoding and NMS run on CPU.
    These tensors are *untrained* logits, not usable detections yet.
    """

    def __init__(self, num_classes: int, width: int = 48):
        super().__init__()
        if num_classes < 1:
            raise ValueError("num_classes must be >= 1")
        self.num_classes = num_classes
        self.backbone = TinyBackbone()
        self.l2 = ConvBNAct(32, width, kernel=1)
        self.l3 = ConvBNAct(56, width, kernel=1)
        self.l4 = ConvBNAct(88, width, kernel=1)
        self.smooth2 = DWBlock(width, width)
        self.smooth3 = DWBlock(width, width)
        channels = 1 + num_classes + 4
        self.head2 = nn.Sequential(DWBlock(width, width), nn.Conv2d(width, channels, 1))
        self.head3 = nn.Sequential(DWBlock(width, width), nn.Conv2d(width, channels, 1))
        self.head4 = nn.Sequential(DWBlock(width, width), nn.Conv2d(width, channels, 1))

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        p2, p3, p4 = self.backbone(x)
        f4 = self.l4(p4)
        f3 = self.smooth3(self.l3(p3) + F.interpolate(f4, size=p3.shape[-2:], mode="nearest"))
        f2 = self.smooth2(self.l2(p2) + F.interpolate(f3, size=p2.shape[-2:], mode="nearest"))
        return self.head2(f2), self.head3(f3), self.head4(f4)


class AnchorRefiner(nn.Module):
    """Patch-level heatmap model for semantic snap anchors.

    Output is [N, A + 1, H/4, W/4]: A keypoint heatmaps, plus a
    foreground/symbol-stroke mask. Decode heatmap peaks on CPU and
    refine against original high-resolution pixels.
    """

    def __init__(self, anchor_count: int = 4):
        super().__init__()
        if anchor_count < 1:
            raise ValueError("anchor_count must be >= 1")
        self.anchor_count = anchor_count
        self.stem = ConvBNAct(3, 16, 3, 2)
        self.encoder = nn.Sequential(
            DWBlock(16, 24, 2),
            DWBlock(24, 32),
            DWBlock(32, 32),
            DWBlock(32, 32),
        )
        self.heatmaps = nn.Conv2d(32, anchor_count + 1, 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.heatmaps(self.encoder(self.stem(x)))


def parameter_counts(num_classes: int = 16, anchor_count: int = 4) -> dict[str, int]:
    models = {"symbol_detector": SymbolDetector(num_classes), "anchor_refiner": AnchorRefiner(anchor_count)}
    return {k: sum(p.numel() for p in v.parameters()) for k, v in models.items()}
