Stop-Service FrameServer -Force
cmake -S . -B build -A x64
cmake --build build --config Release

regsvr32 .\build\Release\SimpleVcamSource.dll
.\build\Release\RegisterVcam.exe
