import argparse
import json
import math
import struct
import subprocess
import sys


BUCKET_SECONDS = 0.5


def main():
    args = parse_args()
    duration = read_duration(args.input)
    if duration < args.min_duration:
        raise RuntimeError(f"Video is too short for {args.min_duration}s clips")

    rms = read_audio_rms(args.input, args.track)
    if not rms:
        raise RuntimeError("No audio samples found")

    clips = find_clips(
        rms,
        duration,
        args.count,
        args.min_duration,
        args.max_duration,
    )
    print(json.dumps(clips, ensure_ascii=False))


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--min-duration", type=int, required=True)
    parser.add_argument("--max-duration", type=int, required=True)
    parser.add_argument("--track", action="append", default=[])
    return parser.parse_args()


def read_duration(path):
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            path,
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return float(result.stdout.strip())


def read_audio_rms(path, tracks):
    sample_rate = 8000
    samples_per_bucket = max(1, round(sample_rate * BUCKET_SECONDS))
    command = ["ffmpeg", "-v", "error", "-threads", "1", "-i", path]

    selections = parse_tracks(tracks)
    if selections:
        command += ["-filter_complex", audio_filter(selections), "-map", "[aout]"]
    else:
        command += ["-vn", "-ac", "1"]

    command += ["-ar", str(sample_rate), "-f", "s16le", "pipe:1"]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    buckets = []
    total_square = 0
    samples = 0
    while True:
        data = process.stdout.read(16384)
        if not data:
            break
        sample_count = len(data) // 2
        for (sample,) in struct.iter_unpack("<h", data[: sample_count * 2]):
            total_square += sample * sample
            samples += 1
            if samples >= samples_per_bucket:
                buckets.append(math.sqrt(total_square / samples) / 32768.0)
                total_square = 0
                samples = 0

    stderr = process.stderr.read().decode("utf-8", errors="replace")
    exit_code = process.wait()
    if exit_code != 0:
        raise RuntimeError(stderr.strip() or "FFmpeg audio analysis failed")
    if samples:
        buckets.append(math.sqrt(total_square / samples) / 32768.0)
    return buckets


def parse_tracks(values):
    selections = []
    for value in values:
        stream, volume = value.split(":", 1)
        selections.append((int(stream), float(volume)))
    return selections


def audio_filter(selections):
    parts = []
    labels = []
    for index, (stream, volume) in enumerate(selections):
        label = f"a{index}"
        labels.append(f"[{label}]")
        parts.append(f"[0:{stream}]volume={volume:.3f},aformat=sample_fmts=fltp:channel_layouts=mono[{label}]")
    if len(selections) == 1:
        parts.append("[a0]anull[aout]")
    else:
        parts.append("".join(labels) + f"amix=inputs={len(selections)}:duration=longest:normalize=0[aout]")
    return ";".join(parts)


def find_clips(rms, duration, requested_count, min_duration, max_duration):
    normalized = normalize(rms)
    avg = sum(normalized) / len(normalized)
    variance = sum((value - avg) ** 2 for value in normalized) / len(normalized)
    std = math.sqrt(variance)
    peak_floor = max(avg + std * 0.58, avg * 1.38)
    keep_floor = max(avg + std * 0.10, avg * 1.08)
    max_buckets = max(1, round(max_duration / BUCKET_SECONDS))
    min_buckets = min(max_buckets, max(1, round(min_duration / BUCKET_SECONDS)))

    moments = []
    for index, value in enumerate(normalized):
        if not is_peak(normalized, index, peak_floor):
            continue

        left = expand_left(normalized, index, keep_floor, max_buckets)
        right = expand_right(normalized, index + 1, keep_floor, max_buckets)
        left = max(0, left - round(2.0 / BUCKET_SECONDS))
        right = min(len(normalized), right + round(3.0 / BUCKET_SECONDS))
        left, right = fit_range(left, right, index, min_buckets, max_buckets, len(normalized))

        clip_duration = max(min_duration, min(max_duration, (right - left) * BUCKET_SECONDS))
        start = min(left * BUCKET_SECONDS, max(0, duration - clip_duration))
        score = score_range(normalized, left, right, avg)
        moments.append({"start": start, "duration": clip_duration, "score": score})

    moments.sort(key=lambda item: item["score"], reverse=True)
    selected = []
    detect_all = requested_count == 0
    for moment in moments:
        if any(overlaps(moment, existing) for existing in selected):
            continue
        selected.append(moment)
        if not detect_all and len(selected) >= requested_count:
            break

    selected.sort(key=lambda item: item["start"])
    return [
        {
            "start": round(item["start"], 3),
            "duration": round(item["duration"], 3),
            "score": round(item["score"], 6),
        }
        for item in selected
    ]


def normalize(values):
    low = min(values)
    high = max(values)
    width = max(0.000001, high - low)
    return [(value - low) / width for value in values]


def is_peak(values, index, floor):
    value = values[index]
    previous_value = values[index - 1] if index > 0 else -1
    next_value = values[index + 1] if index + 1 < len(values) else -1
    return value >= floor and value >= previous_value and value >= next_value


def expand_left(values, index, floor, max_buckets):
    left = index
    quiet = 0
    while left > 0 and index - left < max_buckets // 2:
        if values[left - 1] >= floor or quiet < 3:
            quiet = 0 if values[left - 1] >= floor else quiet + 1
            left -= 1
        else:
            break
    return left


def expand_right(values, index, floor, max_buckets):
    right = index
    anchor = index
    quiet = 0
    while right < len(values) and right - anchor < max_buckets // 2:
        if values[right] >= floor or quiet < 3:
            quiet = 0 if values[right] >= floor else quiet + 1
            right += 1
        else:
            break
    return right


def fit_range(left, right, anchor, min_buckets, max_buckets, total_buckets):
    while right - left < min_buckets and right - left < max_buckets and (left > 0 or right < total_buckets):
        if left > 0:
            left -= 1
        if right - left >= min_buckets or right - left >= max_buckets:
            break
        if right < total_buckets:
            right += 1

    if right - left > max_buckets:
        extra = right - left - max_buckets
        trim_left = min(extra // 2, anchor - left)
        left += trim_left
        right -= extra - trim_left
    return left, right


def score_range(values, left, right, average):
    segment = values[left:right]
    if not segment:
        return 0
    local_average = sum(segment) / len(segment)
    spike = max(segment) - average
    return local_average * 0.9 + max(0, spike) * 1.5 + min(0.04, len(segment) * 0.0007)


def overlaps(left, right):
    gap = 2.0
    return left["start"] < right["start"] + right["duration"] + gap and right["start"] < left["start"] + left["duration"] + gap


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
