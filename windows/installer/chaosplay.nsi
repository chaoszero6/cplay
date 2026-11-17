; CPlay for Windows installer (NSIS, built on Linux with makensis).
; Internal names (install folder, registry keys, settings) stay "ChaosPlay" so updates keep everything.
; Per-user install: no administrator prompt, uninstalls cleanly.
Unicode true
; 64-bit installer to match the 64-bit app
Target amd64-unicode
SetCompressor /SOLID lzma
!include "MUI2.nsh"

!ifndef VERSION
  !define VERSION "0.0.0"
!endif
!ifndef SRCDIR
  !define SRCDIR "..\build-windows\deploy\CPlay"
!endif
!ifndef OUTFILE
  !define OUTFILE "CPlay-Setup.exe"
!endif

Name "CPlay"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\ChaosPlay"
InstallDirRegKey HKCU "Software\ChaosWares\ChaosPlay" "InstallDir"
RequestExecutionLevel user
BrandingText "CPlay ${VERSION}"
VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "CPlay"
VIAddVersionKey "FileDescription" "CPlay video player setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "CompanyName" "ChaosWares"
VIAddVersionKey "LegalCopyright" "ChaosWares"

!define MUI_ICON "..\assets\chaosplay.ico"
!define MUI_UNICON "..\assets\chaosplay.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\CPlay.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Start CPlay"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\ChaosPlay"
!define PROGID "ChaosPlay.Video"

; "Open with CPlay" for one extension (does not take over the default player)
!macro Assoc EXT
  WriteRegStr HKCU "Software\Classes\.${EXT}\OpenWithProgids" "${PROGID}" ""
  WriteRegStr HKCU "Software\Classes\Applications\CPlay.exe\SupportedTypes" ".${EXT}" ""
!macroend
!macro UnAssoc EXT
  DeleteRegValue HKCU "Software\Classes\.${EXT}\OpenWithProgids" "${PROGID}"
!macroend
!define EXTS "mp4 m4v mkv webm avi mov wmv flv ts m2ts mts mpg mpeg vob 3gp ogv mka mp3 m4a flac"

Section "CPlay (required)" SecMain
  SectionIn RO
  ; Close a running copy so its files can be replaced
  nsExec::Exec 'taskkill /IM CPlay.exe /F'
  nsExec::Exec 'taskkill /IM ChaosPlay.exe /F'
  ; Upgrading from "ChaosPlay" (0.1 / 0.2.x): remove the old names
  Delete "$INSTDIR\ChaosPlay.exe"
  Delete "$SMPROGRAMS\ChaosPlay.lnk"
  Delete "$DESKTOP\ChaosPlay.lnk"
  DeleteRegKey HKCU "Software\Classes\Applications\ChaosPlay.exe"
  SetOutPath "$INSTDIR"
  File /r "${SRCDIR}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\ChaosWares\ChaosPlay" "InstallDir" "$INSTDIR"

  CreateShortcut "$SMPROGRAMS\CPlay.lnk" "$INSTDIR\CPlay.exe"

  ; Add/Remove Programs entry
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "CPlay"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "ChaosWares"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\CPlay.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1

  ; File types: CPlay appears under "Open with"
  WriteRegStr HKCU "Software\Classes\${PROGID}" "" "Video (CPlay)"
  WriteRegStr HKCU "Software\Classes\${PROGID}\DefaultIcon" "" "$INSTDIR\CPlay.exe,0"
  WriteRegStr HKCU "Software\Classes\${PROGID}\shell\open\command" "" '"$INSTDIR\CPlay.exe" "%1"'
  WriteRegStr HKCU "Software\Classes\Applications\CPlay.exe\shell\open\command" "" '"$INSTDIR\CPlay.exe" "%1"'
  WriteRegStr HKCU "Software\Classes\Applications\CPlay.exe" "FriendlyAppName" "CPlay"
  !insertmacro Assoc mp4
  !insertmacro Assoc m4v
  !insertmacro Assoc mkv
  !insertmacro Assoc webm
  !insertmacro Assoc avi
  !insertmacro Assoc mov
  !insertmacro Assoc wmv
  !insertmacro Assoc flv
  !insertmacro Assoc ts
  !insertmacro Assoc m2ts
  !insertmacro Assoc mts
  !insertmacro Assoc mpg
  !insertmacro Assoc mpeg
  !insertmacro Assoc vob
  !insertmacro Assoc 3gp
  !insertmacro Assoc ogv
  !insertmacro Assoc mka
  !insertmacro Assoc mp3
  !insertmacro Assoc m4a
  !insertmacro Assoc flac
  ; Tell Explorer the file associations changed
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

Section "Desktop shortcut" SecDesktop
  CreateShortcut "$DESKTOP\CPlay.lnk" "$INSTDIR\CPlay.exe"
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /IM CPlay.exe /F'
  Delete "$SMPROGRAMS\CPlay.lnk"
  Delete "$DESKTOP\CPlay.lnk"
  Delete "$SMPROGRAMS\ChaosPlay.lnk"
  Delete "$DESKTOP\ChaosPlay.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\Classes\${PROGID}"
  DeleteRegKey HKCU "Software\Classes\Applications\CPlay.exe"
  DeleteRegKey HKCU "Software\Classes\Applications\ChaosPlay.exe"
  !insertmacro UnAssoc mp4
  !insertmacro UnAssoc m4v
  !insertmacro UnAssoc mkv
  !insertmacro UnAssoc webm
  !insertmacro UnAssoc avi
  !insertmacro UnAssoc mov
  !insertmacro UnAssoc wmv
  !insertmacro UnAssoc flv
  !insertmacro UnAssoc ts
  !insertmacro UnAssoc m2ts
  !insertmacro UnAssoc mts
  !insertmacro UnAssoc mpg
  !insertmacro UnAssoc mpeg
  !insertmacro UnAssoc vob
  !insertmacro UnAssoc 3gp
  !insertmacro UnAssoc ogv
  !insertmacro UnAssoc mka
  !insertmacro UnAssoc mp3
  !insertmacro UnAssoc m4a
  !insertmacro UnAssoc flac
  DeleteRegKey HKCU "Software\ChaosWares\ChaosPlay"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
  ; Settings and history in %APPDATA%\ChaosWares\ChaosPlay are kept
SectionEnd

LangString DESC_Main ${LANG_ENGLISH} "The player, its codecs and 'Open with' for video files."
LangString DESC_Desktop ${LANG_ENGLISH} "A CPlay icon on the desktop."
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} $(DESC_Main)
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} $(DESC_Desktop)
!insertmacro MUI_FUNCTION_DESCRIPTION_END
