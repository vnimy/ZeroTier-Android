<h1 align="center">
  <img src="https://github.com/kaaass/ZerotierFix/blob/master/app/src/main/ic_launcher-playstore.png?raw=true" alt="ZeroTier" width="200">
  <br>ZeroTier<br>
</h1>

<h4 align="center">An unofficial ZeroTier Android client based on the official client.</h4>

<p align="center">
  <img src="screenshots/main.png" alt="main" width="150"/>
  <img src="screenshots/peers.png" alt="peers" width="150"/>
  <img src="screenshots/moons.png" alt="moons" width="150"/>
</p>

<p align="center">
    <a href="https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml">
        <img src="https://github.com/kaaass/ZerotierFix/actions/workflows/build-app.yml/badge.svg" alt="Build APP"/>
    </a>
</p>

## Features

- Self-hosted Moon Support
- Add custom planet config via file and URL
- View peers list
- Chinese translation

## Download

Download the latest APK from [zt.vnimy.com](https://zt.vnimy.com/app/zerotier.apk).

The app checks the same server for updates automatically.

## Copyright

The code for this repository is based on the reverse engineering of the official Android client. The
original author is Grant Limberg (glimberg@gmail.com). See [AUTHORS.md](https://github.com/zerotier/ZeroTierOne/blob/master/AUTHORS.md#primary-authors) for more details.

- Zerotier JNI Sdk is located in git submodule `externals/core`
- Original Android client code is located in `com.vnimy.zerotier` (renamed from `com.zerotier.one`)
- App logo is a trademark of `ZeroTier, Inc.` and made by myself. 


## Roadmap

- [X] Add moon config persistent & file config
- [x] Add peer list view
- [x] Support planet config
- [x] Replace pre-built JNI library
- [x] Rewrite & update UI to fit Material Design
- [ ] *WIP* Rewrite whole APP in v2
