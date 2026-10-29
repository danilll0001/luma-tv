package de.hamid.lumatv.stage;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Short synthesized sounds (tick, pop, hop). Nothing is stored or downloaded. */
final class Sfx {
    private static final int RATE = 22050;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final short[] tick = tone(1500, 1500, 18, .30f);
    private final short[] pop = tone(520, 980, 110, .70f);
    private final short[] hop = tone(380, 860, 170, .70f);
    private final short[] back = tone(820, 420, 120, .60f);
    private long lastTick;

    private static short[] tone(float f0, float f1, int ms, float gain) {
        int n = RATE * ms / 1000;
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            phase += 2 * Math.PI * (f0 + (f1 - f0) * t) / RATE;
            double attack = Math.min(1.0, i / (RATE * 0.004));
            out[i] = (short) (Math.sin(phase) * Math.exp(-4 * t) * attack * gain * 32767 * 0.6);
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
