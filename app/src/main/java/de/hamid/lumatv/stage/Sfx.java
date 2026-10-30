package de.hamid.lumatv.stage;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Short synthesized sounds in five themes (tick, pop, hop, back). Nothing is stored or downloaded. */
final class Sfx {
    private static final int RATE = 22050;
    static final int THEMES = 5;
    private final Handler main = new Handler(Looper.getMainLooper());
    private short[] tick, pop, hop, back;
    private long lastTick;

    Sfx() { setTheme(0); }

    /** 0 Pop, 1 Glass, 2 Marimba, 3 Retro, 4 Soft. */
    void setTheme(int theme) {
        switch (theme) {
            case 1:
                tick = tone(1, 2200, 2200, 14, .30f, 6);
                pop = tone(1, 1320, 1320, 260, .55f, 5);
                hop = tone(1, 990, 1480, 320, .55f, 5);
                back = tone(1, 1480, 990, 220, .50f, 5);
                break;
            case 2:
                tick = tone(2, 900, 900, 20, .35f, 8);
                pop = tone(2, 660, 660, 200, .70f, 7);
                hop = tone(2, 523, 784, 220, .70f, 6);
                back = tone(2, 784, 523, 180, .65f, 6);
                break;
            case 3:
                tick = tone(3, 1800, 1800, 10, .20f, 4);
                pop = tone(3, 880, 1320, 80, .30f, 5);
                hop = tone(3, 330, 990, 160, .30f, 3);
                back = tone(3, 990, 330, 140, .30f, 3);
                break;
            case 4:
                tick = tone(0, 900, 900, 24, .22f, 5);
                pop = tone(0, 330, 392, 180, .60f, 4);
                hop = tone(0, 262, 392, 260, .60f, 3.5f);
                back = tone(0, 392, 262, 200, .55f, 4);
                break;
            default:
                tick = tone(0, 1500, 1500, 18, .30f, 4);
                pop = tone(0, 520, 980, 110, .70f, 4);
                hop = tone(0, 380, 860, 170, .70f, 4);
                back = tone(0, 820, 420, 120, .60f, 4);
                break;
        }
    }

    private static short[] tone(int wave, float f0, float f1, int ms, float gain, float decay) {
        int n = RATE * ms / 1000;
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            phase += 2 * Math.PI * (f0 + (f1 - f0) * t) / RATE;
            double s;
            switch (wave) {
                case 1: s = (Math.sin(phase) + .45 * Math.sin(phase * 2.76) + .2 * Math.sin(phase * 5.4)) / 1.65; break;
                case 2: s = (Math.sin(phase) + .5 * Math.sin(phase * 4) + .25 * Math.sin(phase * 10)) / 1.75; break;
                case 3: s = Math.sin(phase) >= 0 ? 1 : -1; break;
                default: s = (Math.sin(phase) + .2 * Math.sin(phase * 2)) / 1.2; break;
            }
            double env = Math.exp(-decay * t) * Math.min(1.0, i / (RATE * 0.004));
            out[i] = (short) (s * env * gain * 32767 * 0.6);
        }
        return out;
    }

    void tick(float volume) {
        long now = SystemClock.uptimeMillis();
        if (now - lastTick < 70) return;
        lastTick = now;
        play(tick, volume * .6f);
    }

    void pop(float volume) { play(pop, volume); }

    void hop(float volume) { play(hop, volume); }

    void back(float volume) { play(back, volume); }

    private void play(final short[] data, float volume) {
        if (volume <= 0f) return;
        try {
            final AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(data, 0, data.length);
            track.setVolume(Math.min(1f, volume));
            track.play();
            main.postDelayed(track::release, data.length * 1000L / RATE + 200);
        } catch (RuntimeException unavailable) {
            // No audio output: stay silent.
        }
    }
}
