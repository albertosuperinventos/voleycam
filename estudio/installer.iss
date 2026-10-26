; Instalador de VoleyCam Estudio (Inno Setup 6)
#define MyAppName "VoleyCam Estudio"
#define MyAppVersion GetEnv("APP_VERSION")
[Setup]
AppId={{6E1E6C9A-3C0B-4A57-9C84-2D5B0F7A11E4}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=Superinventos
DefaultDirName={autopf}\VoleyCam Estudio
DefaultGroupName=VoleyCam Estudio
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=VoleyCamEstudio-Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\VoleyCamEstudio.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear un icono en el escritorio"; GroupDescription: "Iconos:"

[Files]
Source: "dist\VoleyCamEstudio\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\VoleyCam Estudio"; Filename: "{app}\VoleyCamEstudio.exe"
Name: "{autodesktop}\VoleyCam Estudio"; Filename: "{app}\VoleyCamEstudio.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\VoleyCamEstudio.exe"; Description: "Abrir VoleyCam Estudio"; Flags: nowait postinstall skipifsilent
