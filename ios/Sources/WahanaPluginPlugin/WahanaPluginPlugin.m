#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(WahanaPluginPlugin, "WahanaPlugin",
    CAP_PLUGIN_METHOD(getCurrentPosition, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(watchPosition, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(stopWatch, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(checkPermissions, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(requestPermissions, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(isLocationEnabled, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(checkLocationStatus, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(openLocationSettings, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(openAppSettings, CAPPluginReturnPromise);
)

