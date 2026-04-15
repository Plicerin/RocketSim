$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:OPENROCKET_PLAYBACK_ONLY = '1'
$env:OPENROCKET_PLAYBACK_CAPTURE = '0'
$env:JAVA_TOOL_OPTIONS = '-Dsun.java2d.noddraw=true -Dsun.java2d.d3d=false -Dsun.java2d.ddforcevram=true -Dsun.java2d.ddblit=false -Dswing.useflipBufferStrategy=True'

$openRocketCmdPattern = 'info\.openrocket\.swing\.startup\.(OpenRocket|SwingStartup)|OpenRocket-\d+\.\w+-SNAPSHOT\.jar|openrocket\\build\\libs\\OpenRocket-'
$openRocketProcesses = Get-CimInstance Win32_Process |
	Where-Object {
		$_.Name -match '^java(w)?\.exe$' -and
		$_.CommandLine -match $openRocketCmdPattern
	}

if ($openRocketProcesses) {
	$openRocketProcesses | ForEach-Object {
		Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
	}
}

Set-Location 'C:\Users\vrock\Documents\RocketSim\openrocket'
.\gradlew.bat run