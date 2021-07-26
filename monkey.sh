#!/bin/bash

# https://developer.android.com/studio/test/monkey?hl=en
# https://gist.github.com/Pulimet/5013acf2cd5b28e55036c82c91bd56d8

# application package to launch
package="net.cellar.debug"
# activity to launch
activity="net.cellar.UiActivity"
# path to 'local.properties' file which should contain a 'sdk.dir' entry
localproperties="local.properties"
# number of monkey events to generate
count=300

if [ ! -s $localproperties ] 
then
	echo The local.properties file has not been found under $localproperties!
	exit 1
fi

kd=$(whereis -b kdialog)
if [ "$kd" == "kdialog:" ]; then
	echo This script needs kdialog! Install it via \'apt install kdialog\'.
	exit 1
fi

sdkdir=$(cat $localproperties | grep sdk.dir | cut -d '=' -f 2)
cmd=$sdkdir/platform-tools/adb

devs=$($cmd devices | tr "\t" _ | grep device | tail +2 | cut -d '_' -f 1)
declare -a devices
devices=($devs)
devicecount=${#devices[@]}
#echo $devicecount" devices: "${devices[*]} 
if [ $devicecount -gt 1 ]; then
	alt=""
	for d in ${devices[@]}; do
		alt=$alt" "$d" "$d
	done
	sel=$(kdialog --menu "Select device" $alt)
	if [ "$sel" == "" ]; then
		exit 1
	fi
	cmd=$cmd" -s "$sel
fi

# HOME:3 BACK:4 CALL:5 ENDCALL:6 TOGGLE:26 CAMERA:27 BROWSER:64 CONTACTS:207 BRIGHTNESS:220/221 CUT:277 COPY:278 PASTE:279
$cmd shell input keyevent 3

$cmd shell am start -S -W -n $package/$activity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER >output.txt
error=$(cat output.txt | grep Error)
errormsg=$(cat output.txt | grep Error:)
rm output.txt
if [ ! "$error" == "" ]; then
	echo Failed to launch $package/$activity: $errormsg
	kdialog --title "Failed to launch $activity" --passivepopup '' 4	
	exit 1
fi
sleep 2s
mf=$($cmd shell dumpsys activity | grep mFocusedActivity)
if [ "$mf" == "" ]; then
	mf=$($cmd shell dumpsys activity | grep mResumedActivity)
fi	
if [ "$mf" == "" ]; then
	echo No foreground activity!
	kdialog --title "No foreground activity!" --passivepopup '' 4	
	$cmd shell am force-stop $package
	exit 1
fi
mf=$(echo $mf | grep $package)
echo $mf
if [ "$mf" == "" ]; then
	echo The target activity is not in the foreground!
	kdialog --title "The target activity is not in the foreground!" --passivepopup '' 4	
	$cmd shell am force-stop $package
	exit 1
fi
cur_act=$(echo $mf | cut -d ' ' -f 5)
echo "cur_act: "$cur_act
taskid=$(echo $cur_act | head -c -3 | rev | head -c -1 | rev)
echo "taskid: "$taskid
if [ "$taskid" == "" ]; then
	echo Could not determine task id!
	kdialog --title "Could not determine task id!" --passivepopup '' 4	
	$cmd shell am force-stop $package
	exit 1
fi
#kdeconnect-cli -d 4cee197583c45284 ping-msg "Monkey on the loose!"
kdialog --title "Pin app within 8 seconds!" --passivepopup '' 4
$cmd shell am task lock $taskid >/dev/null &
sleep 10s
$cmd shell monkey -p $package -v $count --throttle 100 --pct-syskeys 0 --kill-process-after-error
kdialog --title "Monkey resting now." --passivepopup '' 4
now=$(date "+%F_%T")
$cmd shell screencap /sdcard/monkey.png
$cmd pull /sdcard/monkey.png >/dev/null &
$cmd shell rm /sdcard/monkey.png
mv monkey.png monkey_$now.png
$cmd shell am task lock stop >/dev/null &
$cmd shell am kill $package
path=$(pwd)
qdbus org.freedesktop.FileManager1 /org/freedesktop/FileManager1 org.freedesktop.FileManager1.ShowItems $path/monkey_$now.png ""

