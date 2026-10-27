# -*- mode: python ; coding: utf-8 -*-
import os

# ---- VERSION 单一源：读取 VERSION 并生成 exe 版本资源 ----
try:
    _here = SPECPATH
except NameError:                      # 兜底：从 tgproxy/ 目录执行时
    _here = os.getcwd()
_version = "0.0.0"
_version_file = os.path.join(_here, "VERSION")
if os.path.isfile(_version_file):
    with open(_version_file, encoding="utf-8") as _fh:
        _version = _fh.read().strip() or "0.0.0"


def _write_version_resource(path, version):
    parts = [p for p in version.split(".") if p.isdigit()][:4]
    nums = [int(p) for p in parts] + [0] * (4 - len(parts))
    tpl = """VSVersionInfo(
  ffi=FixedFileInfo(
    filevers=(%d, %d, %d, %d),
    prodvers=(%d, %d, %d, %d),
    mask=0x3f,
    flags=0x0,
    OS=0x40004,
    fileType=0x1,
    subtype=0x0,
    date=(0, 0)
  ),
  kids=[
    StringFileInfo([
      StringTable(
        '080404b0',
        [StringStruct('CompanyName', 'tgproxy'),
         StringStruct('FileDescription', 'TGProxy'),
         StringStruct('FileVersion', '%s'),
         StringStruct('InternalName', 'tgproxy'),
         StringStruct('OriginalFilename', 'tgproxy.exe'),
         StringStruct('ProductName', 'TGProxy'),
         StringStruct('ProductVersion', '%s')])
    ]),
    VarFileInfo([VarStruct('Translation', [2052, 1200])])
  ]
)
""" % (nums[0], nums[1], nums[2], nums[3],
       nums[0], nums[1], nums[2], nums[3],
       version, version)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(tpl)


_verfile = os.path.join(_here, "build", "version_info.txt")
os.makedirs(os.path.dirname(_verfile), exist_ok=True)
_write_version_resource(_verfile, _version)


a = Analysis(
    ['app.py'],
    pathex=[],
    binaries=[],
    datas=[('static', 'static'), ('VERSION', '.')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='tgproxy',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    version=_verfile,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
