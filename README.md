# Family Home

A clean, family-friendly home screen for Google TV (Chromecast with Google TV, Sony Bravia).

- Now playing banner and Continue watching from Jellyfin (everyone's progress, opens Wholphin)
- Recently added, recently opened apps, your app tiles (hold OK to move or hide)
- Family photo wallpaper and screensaver, clock, live weather (Open-Meteo)
- Search, Google TV home button, tile size option

The screen is `app/src/main/assets/index.html`. Opened in a normal browser it runs with example
data, so it doubles as the web test. Inside the app it uses the TV's real apps and data.

Every push to `main` builds `family-home.apk` and publishes it as the latest release.
Install and set up with the TV Tool ("Set up everything").
