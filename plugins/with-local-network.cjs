const { withAndroidManifest } = require('expo/config-plugins');
module.exports = function withLocalNetwork(config, { allowHttp = false } = {}) {
  return withAndroidManifest(config, result => {
    result.modResults.manifest.application[0].$['android:usesCleartextTraffic'] = allowHttp ? 'true' : 'false';
    return result;
  });
};
