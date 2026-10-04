# Adds location/notification permissions and the arrival receivers to the Android manifest
# that `npx cap add android` generates.
import re

path = 'android/app/src/main/AndroidManifest.xml'
xml = open(path, encoding='utf-8').read()

perms = [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_BACKGROUND_LOCATION',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.RECEIVE_BOOT_COMPLETED',
]
add = ''.join(f'    <uses-permission android:name="{p}" />\n' for p in perms if p not in xml)
xml = xml.replace('<application', add + '\n    <application', 1)

receivers = '''
        <receiver android:name=".FenceReceiver" android:exported="false" />
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
'''
xml = xml.replace('</application>', receivers + '    </application>', 1)
open(path, 'w', encoding='utf-8').write(xml)
print('Manifest updated.')
