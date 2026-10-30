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

    /** 0 Water, 1 Chime, 2 Wood, 3 Harp, 4 Air: soft, short and quiet enough to hear all day. */
    void setTheme(int theme) {
        switch (theme) {
            case 1:
                tick = tone(1, 1319, 1319, 14, .06f, 6, 3);
                pop = tone(1, 659, 659, 320, .30f, 5, 6);
                hop = join(tone(1, 523, 523, 140, .26f, 4, 6), tone(1, 784, 784, 300, .28f, 5, 6));
                back = join(tone(1, 784, 784, 120, .24f, 4, 6), tone(1, 523, 523, 240, .26f, 5, 6));
                break;
            case 2:
                tick = tone(2, 620, 620, 9, .06f, 8, 1.5f);
                pop = tone(2, 330, 330, 110, .34f, 9, 2);
                hop = join(tone(2, 262, 262, 90, .30f, 9, 2), tone(2, 392, 392, 140, .32f, 9, 2));
                back = join(tone(2, 392, 392, 90, .28f, 9, 2), tone(2, 262, 262, 130, .30f, 9, 2));
                break;
            case 3:
                tick = tone(3, 1046, 1046, 12, .05f, 6, 2);
                pop = tone(3, 880, 880, 420, .26f, 6, 4);
                hop = join(tone(3, 523, 523, 100, .22f, 5, 4), tone(3, 659, 659, 100, .22f, 5, 4),
                        tone(3, 784, 784, 320, .26f, 6, 4));
                back = join(tone(3, 784, 784, 100, .20f, 5, 4), tone(3, 659, 659, 100, .20f, 5, 4),
                        tone(3, 523, 523, 260, .24f, 6, 4));
                break;
            case 4:
                tick = tone(0, 440, 440, 18, .05f, 4, 5);
                pop = tone(0, 330, 330, 240, .30f, 3, 40);
                hop = tone(0, 262, 392, 320, .30f, 3, 60);
                back = tone(0, 392, 262, 260, .28f, 3, 40);
                break;
            default:
                tick = tone(0, 700, 520, 24, .08f, 4, 4);
                pop = tone(0, 560, 340, 140, .34f, 5, 6);
                hop = tone(0, 392, 523, 240, .32f, 4, 20);
                back = tone(0, 523, 392, 180, .30f, 4, 14);
                break;
        }
    }

    private static short[] join(short[]... parts) {
        int total = 0;
        for (short[] part : parts) total += part.length;
        short[] out = new short[total];
        int at = 0;
        for (short[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static short[] tone(int wave, float f0, float f1, int ms, float gain, float decay, float attackMs) {
        int n = RATE * ms / 1000;
        short[] out = new short[n];
        double phase = 0;
        double attack = Math.max(1.0, RATE * attackMs / 1000.0);
        double tail = RATE * 0.012;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            phase += 2 * Math.PI * (f0 + (f1 - f0) * t) / RATE;
            double s;
            switch (wave) {
                case 1: s = (Math.sin(phase) + .35 * Math.sin(phase * 2) + .15 * Math.sin(phase * 3)) / 1.5; break;
                case 2: s = (Math.sin(phase) + .25 * Math.sin(phase * 2)) / 1.25; break;
                case 3: s = (Math.sin(phase) + .5 * Math.sin(phase * 2) * Math.exp(-3 * t)
                        + .3 * Math.sin(phase * 3) * Math.exp(-5 * t)) / 1.8; break;
                default: s = Math.sin(phase); break;
            }
            double env = Math.exp(-decay * t) * Math.min(1.0, i / attack) * Math.min(1.0, (n - i) / tail);
            out[i] = (short) (s * env * gain * 32767);
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
