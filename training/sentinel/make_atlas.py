"""
Builds the Sentinel face's pose atlas (app/src/main/res/drawable-nodpi/sentinel_atlas.png) and the eye track
(printed as a Kotlin array) from the hooded-figure still Rex chose (17-22 Nov 2026).

48 poses, from "head lowered" through "rising" and "turned aside" back to "facing you", each a small grid of the
figure's brightness (R, already contrast-shaped for digits) and where the figure is (G, so far code rain can fall
behind it). The phone's shader blends between neighbouring poses, so the motion is smooth at any frame rate.

Run:  python3 -I training/sentinel/make_atlas.py <still.png> <out.png>
"""
import sys, math, numpy as np
from PIL import Image, ImageDraw, ImageFilter

src, out = sys.argv[1], sys.argv[2]
base = np.asarray(Image.open(src).convert('RGB'), dtype=np.float32) / 255.0
base = np.clip((base ** 1.15) * np.array([0.92, 0.98, 1.06]), 0, 1)
H, W, _ = base.shape
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

def ease(t): t = min(1, max(0, t)); return t * t * (3 - 2 * t)
def seg(t, a, b): return ease((t - a) / (b - a))
cx, cy = 322.0, 165.0
WEIGHT = np.clip(1.25 - np.sqrt(((xx - cx) / 175) ** 2 + ((yy - cy) / 150) ** 2), 0, 1) ** 1.2

def pose(t):
    pitch = 1.0 - seg(t, 1.6, 3.6) - 0.25 * math.sin(math.pi * seg(t, 2.6, 3.9))
    yaw = seg(t, 3.9, 4.9) - seg(t, 5.4, 6.3)
    return pitch, yaw

def displacement(t):
    pitch, yaw = pose(t)
    dy = WEIGHT * (16 * pitch + (yy - cy) * (-0.07 * pitch))
    dx = WEIGHT * (-26 * yaw + (xx - cx) * (-0.07 * abs(yaw))) + WEIGHT * (yy - cy) * 0.04 * yaw
    return dx, dy

def sample(img, X, Y):
    X = np.clip(X, 0, W - 1.001); Y = np.clip(Y, 0, H - 1.001)
    x0 = X.astype(np.int32); y0 = Y.astype(np.int32)
    fx = (X - x0)[..., None]; fy = (Y - y0)[..., None]
    return (img[y0, x0] * (1 - fx) + img[y0, x0 + 1] * fx) * (1 - fy) + (img[y0 + 1, x0] * (1 - fx) + img[y0 + 1, x0 + 1] * fx) * fy

def moved(px, py, dx, dy):
    qx, qy = px, py
    for _ in range(3):
        ix, iy = int(min(W - 1, max(0, qx))), int(min(H - 1, max(0, qy)))
        qx, qy = px + dx[iy, ix], py + dy[iy, ix]
    return qx, qy

def figure_mask(lum):
    sm = np.asarray(Image.fromarray((lum * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(6)), dtype=np.float32) / 255.0
    b = Image.fromarray(((sm > 0.06) * 255).astype(np.uint8)).copy()   # smoothed first: the still's own rain isn't figure
    for p in [(0, 0), (b.width - 1, 0), (0, b.height // 3), (b.width - 1, b.height // 3)]:
        if b.getpixel(p) == 0: ImageDraw.floodfill(b, p, 128)
    return (np.asarray(b) != 128).astype(np.float32)

ELL = ((((xx - 322) / 158) ** 2 + ((yy - 178) / 188) ** 2) < 1).astype(np.float32)[..., None]

# crop around the figure (portrait-friendly), and the size of one pose in the atlas
X0, X1, Y0, Y1 = 120, 520, 0, 360
FW, FH, COLS, ROWS = 100, 90, 8, 6
N = COLS * ROWS
atlas = np.zeros((ROWS * FH, COLS * FW, 3), np.uint8)
eyes = []
for i in range(N):
    t = 0.7 + (6.4 - 0.7) * i / (N - 1)
    dx, dy = displacement(t)
    img = sample(base, xx - dx, yy - dy)[Y0:Y1, X0:X1]
    lum = img.mean(axis=2)
    ell = sample(ELL, xx - dx, yy - dy)[Y0:Y1, X0:X1, 0]
    mask = np.maximum(figure_mask(lum), (ell > 0.5).astype(np.float32))
    small = np.asarray(Image.fromarray((lum * 255).astype(np.uint8)).resize((FW, FH), Image.BOX), dtype=np.float32) / 255.0
    blur = np.asarray(Image.fromarray((small * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2)), dtype=np.float32) / 255.0
    sharp = np.clip(small + 1.6 * (small - blur), 0, 1)
    v = np.clip((sharp - 0.03) / 0.20, 0, 1) ** 0.65
    m = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).resize((FW, FH), Image.BOX), dtype=np.float32) / 255.0
    r, c = divmod(i, COLS)
    atlas[r * FH:(r + 1) * FH, c * FW:(c + 1) * FW, 0] = (v * 255).astype(np.uint8)
    atlas[r * FH:(r + 1) * FH, c * FW:(c + 1) * FW, 1] = (m * 255).astype(np.uint8)
    ex, ey = moved(357, 148, dx, dy)
    eyes.append(((ex - X0) / (X1 - X0), (ey - Y0) / (Y1 - Y0)))
Image.fromarray(atlas).save(out, optimize=True)
print('EYE = floatArrayOf(' + ', '.join(f'{x:.4f}f, {y:.4f}f' for x, y in eyes) + ')')
