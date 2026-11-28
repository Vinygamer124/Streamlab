#!/usr/bin/env python3
"""Sửa các plate Rest (assets/rest/pNNN.webp, RGBA 1920x1080).

1. Khung "GAME" góc trái trên: khử viền trắng trên cùng + hào quang trắng cạnh trái.
2. p021: xoá vệt tím thừa phía dưới.
3. p023-p033: xoá dải "gap" xanh/teal giữa các card.
4. p050-p056: thêm lại chữ "Power" (lấy từ p049).
"""
import sys, os, shutil
import numpy as np
import cv2
from PIL import Image

SRC = sys.argv[1]            # thư mục assets/rest gốc
DST = sys.argv[2]            # thư mục ghi kết quả
os.makedirs(DST, exist_ok=True)

PANEL_W = 543                # bề rộng panel GAME (px)
LEFT_BAND = 32               # dải hào quang cạnh trái (px)


def load(i):
    return np.array(Image.open(f"{SRC}/p{i:03d}.webp").convert("RGBA"))


def save(i, a):
    Image.fromarray(a.astype(np.uint8), "RGBA").save(
        f"{DST}/p{i:03d}.webp", lossless=False, quality=96, alpha_quality=100, method=3)


def lum(a):
    return a[..., :3].astype(np.float32).mean(2)


# ---------------------------------------------------------------- 1. góc trái trên
def fix_topleft(a):
    if a[100:900, 100:400, 3].mean() < 150:       # panel gần như trong suốt → bỏ qua
        return a
    a = a.copy()
    rgb = a[..., :3]
    L = lum(a)
    H = 28
    # --- viền trắng trên cùng + hào quang góc trên (x 0..PANEL_W)
    x1 = PANEL_W + 4
    base = np.median(L[14:H, :x1], axis=0)                      # nền chuẩn theo cột
    over = L[:H, :x1] - base[None, :]
    mask = (over > 5).astype(np.uint8)
    mask[:7, :] = 1                                              # 7 hàng trên cùng luôn xoá
    mask = cv2.dilate(mask, np.ones((3, 3), np.uint8))
    m = np.zeros(L.shape, np.uint8)
    m[:H, :x1] = mask
    # tiền xử lý: tính nền sạch bằng inpaint Telea
    clean = cv2.inpaint(np.ascontiguousarray(rgb), m, 5, cv2.INPAINT_TELEA)
    # làm mượt ngang dải đã vá cho khỏi vằn
    blur = cv2.GaussianBlur(clean, (0, 0), sigmaX=5, sigmaY=1.5)
    sel = cv2.GaussianBlur(m.astype(np.float32), (0, 0), 1.2)[..., None]
    sel = np.clip(sel * 1.0, 0, 1)
    out = rgb.astype(np.float32)
    out = out * (1 - sel) + (0.5 * clean + 0.5 * blur) * sel
    rgb = out
    # --- hào quang trắng cạnh trái: ước lượng trường hào quang G(x,y) = nền - nền_xa rồi trừ đi
    h = a.shape[0]
    RW = 76
    ref = rgb[:, 130:190].mean(1)                                # (h,3) nền thật của panel
    rl = ref.mean(1)
    med = np.median(rl[60:1000])
    patch = rgb[:, :RW]
    pl = patch.mean(2)
    bad = (rl > max(70, med * 2.5)) | ((pl[:, 36:100] - rl[:, None]) > 45).any(1)
    bad = cv2.dilate(bad.astype(np.uint8)[:, None], np.ones((9, 1), np.uint8))[:, 0] > 0
    ys = np.arange(h)
    good = ~bad
    G = patch - ref[:, None, :]                                   # (h,RW,3)
    Gi = np.empty_like(G)
    for x in range(RW):
        for c in range(3):
            Gi[:, x, c] = np.interp(ys, ys[good], G[good, x, c])
    Gi = cv2.GaussianBlur(Gi, (0, 0), sigmaX=1.0, sigmaY=4)
    Gi = np.maximum(Gi, 0)
    w = np.ones(RW, np.float32)
    w[56:] = np.linspace(1, 0, RW - 56)
    rgb[:, :RW] = np.clip(patch - Gi * w[None, :, None], 0, 255)
    a[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    return a


# ---------------------------------------------------------------- 2. p021 vệt tím
def fix_p021():
    a = load(21)
    ref = load(20)
    x0, x1, y0, y1 = 1060, 1250, 915, 1072
    src = cv2.GaussianBlur(ref, (0, 0), 9)
    m = np.zeros(a.shape[:2], np.float32)
    m[y0:y1, x0:x1] = 1
    m = cv2.GaussianBlur(m, (0, 0), 7)
    m = np.clip(m * 1.6, 0, 1)[..., None]
    out = a.astype(np.float32) * (1 - m) + src.astype(np.float32) * m
    return np.clip(out, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- 3. gap giữa các card
def fix_gaps(a):
    a = a.copy()
    r, g, b = [a[..., k].astype(np.float32) for k in range(3)]
    ex = b - (r + g) / 2
    Y0, Y1 = 480, 960
    for lo, hi in ((850, 950), (1230, 1310)):
        prof = ex[500:780, lo:hi].mean(0)
        pk = prof.max()
        if pk < 30:
            continue
        c = lo + int(prof.argmax())
        thr = max(12, 0.25 * pk)
        c0 = c
        while c0 > lo and prof[c0 - 1 - lo] > thr: c0 -= 1
        c1 = c
        while c1 < hi - 1 and prof[c1 + 1 - lo] > thr: c1 += 1
        c0 -= 3; c1 += 4
        right = a[:, c1 + 1:c1 + 5].astype(np.float32).mean(1)    # (h,4)
        seg = a[:, c0:c1 + 1].astype(np.float32)
        dev = np.abs(seg - right[:, None, :]).sum(2).mean(1)
        rows = np.zeros(a.shape[0], bool)
        rows[Y0:Y1] = dev[Y0:Y1] > 25
        rows = cv2.dilate(rows.astype(np.uint8)[:, None], np.ones((9, 1), np.uint8))[:, 0].astype(np.float32)
        rows = cv2.GaussianBlur(rows[:, None], (0, 0), sigmaX=0.1, sigmaY=2)[:, 0]
        wcol = np.ones(c1 - c0 + 1, np.float32)
        wcol[:2] = [0.4, 0.8]
        wt = rows[:, None] * wcol[None, :]
        new = np.repeat(right[:, None, :], c1 - c0 + 1, axis=1)
        out = seg * (1 - wt[..., None]) + new * wt[..., None]
        a[:, c0:c1 + 1] = np.clip(out, 0, 255).astype(np.uint8)
    return a


# ---------------------------------------------------------------- 4. chữ Power
ENTER_TOP = {49: 586, 50: 539, 51: 519, 52: 511, 53: 508, 54: 507, 55: 508, 56: 511}
PX0, PX1, PY0, PY1 = 1204, 1292, 494, 528      # hộp chữ Power trong p049


def power_label():
    a = load(49)
    reg = a[PY0:PY1, PX0:PX1, :3].astype(np.float32).mean(2)
    bg = np.percentile(reg, 30)
    pk = np.percentile(reg, 99.7)
    m = np.clip((reg - bg) / max(1, pk - bg), 0, 1)
    m[:2, :] = 0; m[-2:, :] = 0
    return m


def add_power(i, label):
    a = load(i)
    dy = ENTER_TOP[i] - ENTER_TOP[49]
    h, w = label.shape
    ty = PY0 + dy
    # 1) tối dần vùng phía trên panel để chữ nằm trên nền panel
    Hh, Ww = a.shape[:2]
    top = ty - 16
    dark = np.zeros((Hh, Ww), np.float32)
    dark[max(top, 0):500, 1190:1830] = 1
    dark = cv2.GaussianBlur(dark, (0, 0), sigmaX=6, sigmaY=5)
    ramp = np.zeros(Hh, np.float32)
    ramp[top:500] = np.linspace(0, 1, 500 - top)
    ramp = cv2.GaussianBlur(ramp[:, None], (0, 0), 0.1, sigmaY=5)[:, 0]
    wgt = np.minimum(dark, ramp[:, None]) * 0.85
    rgb = a[..., :3].astype(np.float32) * (1 - wgt[..., None])
    # 2) dán chữ
    col = np.array([158, 161, 161], np.float32)
    mm = np.zeros((Hh, Ww), np.float32)
    mm[ty:ty + h, PX0:PX0 + w] = label
    rgb = rgb * (1 - mm[..., None]) + col * mm[..., None]
    a2 = a.copy()
    a2[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    a2[..., 3] = np.maximum(a[..., 3], (mm * 255).astype(np.uint8))
    return a2


# ---------------------------------------------------------------- main
for f in os.listdir(SRC):
    if not f.endswith(".webp"):
        shutil.copy(f"{SRC}/{f}", f"{DST}/{f}")

lab = power_label()
for i in range(60):
    a = load(i)
    if i == 21:
        a = fix_p021()
    if 23 <= i <= 33:
        a = fix_gaps(a)
    if 50 <= i <= 56:
        a = add_power(i, lab)
    a = fix_topleft(a)
    save(i, a)
print("done")
