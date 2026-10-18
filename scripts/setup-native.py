#!/usr/bin/env python3
"""Fetch pinned native dependencies into ignored, project-local storage."""
from pathlib import Path
import argparse, concurrent.futures, hashlib, json, subprocess, tarfile, urllib.request
ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / '.local-private/deps'
PDFIUM = {
 'android-arm64': 'e9bab2929443d3d881166af9ec1d191c76ecdd37effb07a58743a7d7b507e149',
 'mac-arm64': '7ea647c7a7a9c1169be3f947000bc071ece2fade2cd6bd7e401e3675c6560cce',
 'ios-device-arm64': '9bd1a444edab9f9610cd15beb30af0fa2c18357317117da2e9c292543a267139',
 'ios-simulator-arm64': 'b66dcd38508cb39fbd76fbf8cf9210feddb8960653bfbaf2e670592fc799d119',
}
MODELS = {
 'q4': ('tencent/Hy-MT2-1.8B-GGUF', 'a0c709d9fac510f2c807aa3af52872340dc37a4a', 'Hy-MT2-1.8B-Q4_K_M.gguf', 'dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699'),
 'iq3': ('mradermacher/Hy-MT2-1.8B-i1-GGUF', '9f5c7d98d8b625800775e6197e55c7ed38f2f33a', 'Hy-MT2-1.8B.i1-IQ3_XS.gguf', 'f37758217de25a512e6ff7a728a3cd4fa8f4843041468889bc8a9811a2a894b5'),
 'iq2': ('mradermacher/Hy-MT2-1.8B-i1-GGUF', '9f5c7d98d8b625800775e6197e55c7ed38f2f33a', 'Hy-MT2-1.8B.i1-IQ2_M.gguf', '917ee7286326a2881ba6f519b3e43ed41ff6b1c0db8288ff52f3fae5930080d9'),
}
def digest(path):
 h = hashlib.sha256()
 with path.open('rb') as f:
  for chunk in iter(lambda: f.read(4*1024*1024), b''): h.update(chunk)
 return h.hexdigest()
def fetch(url, dest, expected=None):
 dest.parent.mkdir(parents=True, exist_ok=True)
 if dest.exists() and (not expected or digest(dest) == expected): return dest
 part = dest.with_suffix(dest.suffix + '.part')
 subprocess.run(['curl', '--fail', '--location', '--retry', '4', '--continue-at', '-', '--output', str(part), url], check=True)
 actual = digest(part)
 if expected and actual != expected:
  part.unlink()
  raise ValueError(f'Checksum mismatch: {dest.name}')
 part.replace(dest)
 print(json.dumps({'downloaded': str(dest.relative_to(ROOT)), 'sha256': actual}), flush=True)
 return dest
def pdfium(platform):
 dst = DEPS/'pdfium'/platform
 if (dst/'include/fpdfview.h').exists(): return
 p = fetch(f'https://github.com/bblanchon/pdfium-binaries/releases/download/chromium/8100/pdfium-{platform}.tgz', DEPS/'archives'/f'pdfium-{platform}.tgz', PDFIUM[platform])
 dst.mkdir(parents=True, exist_ok=True)
 with tarfile.open(p) as t: t.extractall(dst, filter='data')
def model(key):
 repo, revision, name, checksum = MODELS[key]
 return fetch(f'https://huggingface.co/{repo}/resolve/{revision}/{name}', ROOT/'.local-private/models'/name, checksum)
def main():
 p=argparse.ArgumentParser();p.add_argument('--models', nargs='*', choices=MODELS, default=[]); p.add_argument('--platforms', nargs='*', choices=PDFIUM, default=['android-arm64', 'mac-arm64', 'ios-device-arm64', 'ios-simulator-arm64']);a=p.parse_args()
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(pdfium,a.platforms))
 llama=DEPS/'llama.cpp'
 if not llama.exists(): subprocess.run(['git', 'clone', '--depth', '1', '--branch', 'v0.7.0', 'https://github.com/ggml-org/llama.cpp.git', str(llama)],check=True)
 revision=subprocess.check_output(['git','-C',str(llama),'rev-parse','HEAD'],text=True).strip()
 if revision!='620d2225cc436ab0b001e3200e590e3b286ef9ab': raise ValueError('Unexpected llama.cpp revision')
 fetch('https://raw.githubusercontent.com/nlohmann/json/v3.12.0/single_include/nlohmann/json.hpp', DEPS/'include/nlohmann/json.hpp', 'aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63')
 for key in a.models: model(key)
if __name__=='__main__': main()
