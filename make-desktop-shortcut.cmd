@echo off
setlocal
set "DIR=%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command ^
  "$ws = New-Object -COM WScript.Shell;" ^
  "$lnk = $ws.CreateShortcut(([Environment]::GetFolderPath('Desktop')) + '\mewCode.lnk');" ^
  "$lnk.TargetPath = '%DIR%mewcode.bat';" ^
  "$lnk.WorkingDirectory = '%DIR:~0,-1%';" ^
  "$lnk.WindowStyle = 1;" ^
  "$lnk.Description = 'MewCode - AI Programming Assistant';" ^
  "$lnk.IconLocation = 'shell32.dll,13';" ^
  "$lnk.Save();" ^
  "Add-Type -AssemblyName System.Windows.Forms;" ^
  "[System.Windows.Forms.MessageBox]::Show('Desktop shortcut created:' + [Environment]::NewLine + 'mewCode.lnk', 'MewCode', 'OK', 'Information')"

endlocal
