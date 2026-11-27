// Paste into the browser console while signed in at https://watchface.zepp.com/
// Prints each band linked to your Zepp account with its MAC and auth key.
// Only talks to Zepp's own server. Based on the method in Gadgetbridge's docs.
(async () => {
  try {
    const cookie = (name) => document.cookie.match(name + "=(.*?)(;|$)")?.[1]?.replaceAll("%2C", ",");
    let cred = {};
    const login = cookie("hm-user-login-info");
    if (login) cred = JSON.parse(decodeURIComponent(login))["token_info"];
    else cred = { user_id: cookie("userid"), app_token: cookie("apptoken") };
    if (!cred?.user_id) throw new Error("Not signed in. Sign in at https://watchface.zepp.com/ first, then run this again.");

    const params = new URLSearchParams({
      appid: "2882303761517383915", channel: "play", enableMultiDevice: "true", device_type: "android_phone",
      cv: "151262_8.7.1-play", device: "android_33", v: "2.0", lang: "en_US", country: "US",
    });
    const res = await fetch(`https://api-mifit-us3.zepp.com/users/${cred.user_id}/devices?${params}`, {
      headers: {
        apptoken: cred.app_token, channel: "play", appname: "com.huami.midong", appplatform: "android_phone",
        cv: "151262_8.7.1-play", v: "2.0", lang: "en_US", country: "US",
      },
    });
    const items = (await res.json()).items || [];
    if (!items.length) throw new Error("No band is linked to this account.");

    const rows = items.map((d) => {
      let info = d.additionalInfo;
      try { if (typeof info === "string") info = JSON.parse(info); } catch (_) {}
      const key = info?.auth_key;
      return {
        MAC: d.macAddress,
        device: d.deviceSource ?? d.deviceType,
        active: d.activeStatus,
        key: key ? "0x" + key.replace(/^0x/i, "") : "(no key stored)",
      };
    });
    console.table(rows);
    console.log("Copy the key for your band's MAC address into BandMate (Step 3).");
  } catch (e) { 
    console.error("Could not get the key:", e.message || e);
  }
})();
