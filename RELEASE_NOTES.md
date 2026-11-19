# SpotXero — v2.1.0 (November 20, 2026)

## New Options & Settings

### Playback Controls
- **Double-Tap Artwork to Seek**: Double-tap the left or right side of the now-playing album cover to jump backward or forward in a track.
- **Configurable Seek Duration & Progressive Seek**: Choose how many seconds to skip per tap (from 5 to 30 seconds), and enable progressive seeking to increase the jump duration with rapid repeated taps.
- **Force Local Volume**: Keep Spotify playback volume strictly tied to your physical device volume buttons, preventing remote Spotify Connect devices from overriding your local controls.

### Lyrics & AI Translation
- **Unlimited Lyrics**: View lyrics without running into Spotify's monthly viewing quota.
- **Third-Party Lyrics Providers**: Fetch missing lyrics from external sources (BetterLyrics, lrc.red, LRCLIB, KuGou, NetEase Cloud Music, and Genius), with options to reorder fallback priority or force third-party lyrics across all songs.
- **AI Lyrics Translation & Modes**: Display real-time translations alongside or underneath original lyrics. Choose between literal translation, phonetic transcription, or Romanization into Latin script.
- **Customizable AI Providers & Prompts**: Connect your preferred AI service (OpenRouter, Google Gemini, DeepL, OpenAI, Claude, Mistral, Groq, or any custom API endpoint), adjust DeepL formality levels, or customize your own translation prompt with an in-app connection test.

### Content Filtering
- **Hide Podcasts**: Clean up your Home feed and Search results by removing podcast shows, episodes, and recommendation carousels.
- **Hide Audiobooks**: Declutter your interface by hiding audiobook titles and chapter recommendations across the app.

### Interface & Theming
- **Monet Dynamic Theming**: Automatically tint Spotify's green accents to match your Android wallpaper colors using Material You.
- **RoundyUI**: Apply soft, rounded corners to album art, buttons, cards, and dialog sheets throughout Spotify.
- **Haptic Feedback**: Enable subtle vibration feedback on switches, sliders, and button presses across the SpotXero settings menu.
- **Master Module Switch**: Instantly turn all SpotXero modifications on or off with a single master toggle on the dashboard.

## Improvements
- **Refreshed Dashboard Organization**: Settings are now neatly categorized into Playback, Lyrics, Content, Privacy, and Theme sections with Material 3 Expressive switches.
- **Ad Filtering**: Enhanced network and visual ad suppression to keep music playback clean and uninterrupted.

## Bug Fixes
- Fixed an issue where spoofed account attributes could interfere with initial login and account authentication handshakes.
- Fixed a potential crash when toggling or disabling shuffle mode.
- Fixed an issue in ad filtering where sections containing only sponsored content could fail to clear completely.

## Acknowledgements & Attributions
- [**Metrolist**](https://github.com/metrolistgroup/metrolist): Implementation and UI for third-party lyrics providers, AI lyrics translation, and double-tap artwork to seek.
- [**ReVanced**](https://github.com/revanced): Reverse engineering and logic for force local volume and unlimited lyrics.
- [**ReVancedXposed_Spotify (TheWinner02)**](https://github.com/TheWinner02/ReVancedXposed_Spotify): Hook implementations for Monet Dynamic Theming and RoundyUI.
