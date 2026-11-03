@echo off
setlocal
rem ============================================================
rem  tgproxy fallback launcher
rem
rem  Use this when the normal launch misbehaves:
rem    TGPROXY_TG_WORKERS=250  -> conservative TG concurrency
rem    TGPROXY_FETCH_RACE=0    -> legacy sequential mirror fallback
rem
rem  Place this file either in the repo root or in tgproxy/.
rem  It locates the app in both layouts.
rem
rem  NOTE: environment variables only apply to a NEW process.
rem        Close the running tgproxy first. Otherwise the single
rem        instance lock (port 8764) hands over to the old process
rem        and these values are silently ignored.
rem ============================================================

set "TGPROXY_TG_WORKERS=250"
set "TGPROXY_FETCH_RACE=0"

rem ---- normalize app root: repo root or tgproxy/ ----
set "ROOT=%~dp0"
if not exist "%ROOT%app.py" if exist "%ROOT%tgproxy\app.py" set "ROOT=%~dp0tgproxy\"

cd /d "%ROOT%"

if exist "%ROOT%dist\tgproxy.exe" (
    echo [launch] dist\tgproxy.exe  TG_WORKERS=250  FETCH_RACE=0
    start "" "%ROOT%dist\tgproxy.exe"
    goto :done
)

if exist "%ROOT%tgproxy.exe" (
    echo [launch] tgproxy.exe  TG_WORKERS=250  FETCH_RACE=0
    start "" "%ROOT%tgproxy.exe"
    goto :done
)

where py >nul 2>nul
if not errorlevel 1 (
    echo [launch] py app.py  TG_WORKERS=250  FETCH_RACE=0
    py "%ROOT%app.py"
    goto :done
)

echo [launch] python app.py  TG_WORKERS=250  FETCH_RACE=0
python "%ROOT%app.py"

:done
endlocal
