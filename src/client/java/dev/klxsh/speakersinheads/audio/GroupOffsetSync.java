package dev.klxsh.speakersinheads.audio;

import java.util.List;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;

/** Manual alignment using PCM already in the current native queues. Runs on the sound executor. */
final class GroupOffsetSync {
    record Member(int source, GroupMemberAudioStream stream) {}
    record Result(String status, long spreadFrames) {}
    private record Window(long start, long frame, long end) {}

    private GroupOffsetSync() {}

    static Result align(List<Member> members) {
        if (members.size() < 2) return new Result("waiting for members", 0);
        double rate = members.getFirst().stream().getFormat().getSampleRate();
        long tolerance = Math.max(1, Math.round(rate * 0.006));
        var before = snapshot(members, AL_PLAYING);
        if (before == null) return new Result("waiting for stable queues", 0);
        long spread = spread(before);
        if (spread <= tolerance) return new Result("already aligned", spread);
        // A mixer update may fall between the AL queries. Confirm that the discrepancy persists.
        var confirmed = snapshot(members, AL_PLAYING);
        if (confirmed == null) return new Result("waiting for stable queues", 0);
        spread = spread(confirmed);
        if (spread <= tolerance) return new Result("already aligned", spread);
        if (target(confirmed) < 0) return new Result("no shared buffered frame", spread);

        int[] sources = members.stream().mapToInt(Member::source).toArray();
        alSourcePausev(sources);
        try {
            var paused = snapshot(members, AL_PAUSED);
            if (paused == null) return new Result("queue changed before alignment", spread);
            spread = spread(paused);
            if (spread <= tolerance) return new Result("already aligned", spread);
            long frame = target(paused);
            if (frame < 0) return new Result("no shared buffered frame", spread);
            for (int i = 0; i < sources.length; i++) {
                var window = paused[i];
                if (window.frame() != frame) alSourcei(sources[i], AL_SAMPLE_OFFSET, Math.toIntExact(frame - window.start()));
            }
            return new Result("aligned in place", spread);
        } finally {
            // An exhausted source may have stopped between the sample and pause. Leave it stopped.
            int count = 0;
            for (int source : sources) {
                if (alGetSourcei(source, AL_SOURCE_STATE) == AL_PAUSED) sources[count++] = source;
            }
            if (count > 0) alSourcePlayv(java.util.Arrays.copyOf(sources, count));
        }
    }

    private static Window[] snapshot(List<Member> members, int state) {
        var windows = new Window[members.size()];
        for (int i = 0; i < windows.length; i++) {
            var member = members.get(i);
            int source = member.source();
            if (alGetSourcei(source, AL_SOURCE_STATE) != state) return null;
            long start = member.stream().queueStartFrame(alGetSourcei(source, AL_BUFFERS_QUEUED));
            long end = member.stream().currentFrameIndex();
            long frame = start + alGetSourcei(source, AL_SAMPLE_OFFSET);
            if (start < 0 || frame < start || frame >= end) return null;
            windows[i] = new Window(start, frame, end);
        }
        return windows;
    }

    private static long spread(Window[] windows) {
        long earliest = Long.MAX_VALUE, latest = 0;
        for (var w : windows) {
            earliest = Math.min(earliest, w.frame());
            latest = Math.max(latest, w.frame());
        }
        return latest - earliest;
    }

    private static long target(Window[] windows) {
        long frame = 0, end = Long.MAX_VALUE;
        for (var w : windows) {
            frame = Math.max(frame, w.frame());
            end = Math.min(end, w.end());
        }
        // Leave at least one millisecond before the end at typical output rates.
        return end - frame >= 48 ? frame : -1;
    }
}
