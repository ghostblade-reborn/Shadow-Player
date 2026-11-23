/*
 * gbffmpeg.c — JNI bridge over GhostBlade Player's OWN FFmpeg 7.1.1 build
 * (LGPL, network compiled out; see codec-lab/build-ffmpeg.sh).
 *
 * This is the SW / HW+ audio heart of the MX carbon copy: it demuxes a
 * local file with libavformat, decodes ONE audio stream to interleaved
 * S16 PCM with libavcodec + libswresample, and hands the PCM to Kotlin,
 * where SwAudioSession (FFmpegEngine.kt) feeds an AudioTrack.
 *
 * Design notes
 * ------------
 * - One GbSession per open file. All session state lives in the struct;
 *   a pthread mutex serialises decode/seek/gain/speed calls. close()
 *   must be called when no decode is in flight (the Kotlin side
 *   guarantees this by stopping its decode thread first).
 * - Content timeline: all timestamps reported to Kotlin are in
 *   milliseconds on the file's content timeline, rebased so the audio
 *   stream's start_time is 0 (some containers, e.g. MPEG-TS, start
 *   near 1.4 s; without the rebase the player's clock would be offset).
 * - Speed: the resampler output rate is in_rate / speed, so the same
 *   AudioTrack rate plays the content faster/slower. This is a tape-
 *   style rate change — pitch shifts with speed. Pitch-corrected tempo
 *   (atempo/SoundTouch class) is NOT possible with libswresample alone
 *   and avfilter is disabled in this build; see FFmpegEngine.kt docs.
 * - Seek: avformat_seek_file BACKWARD to the keyframe at or before the
 *   target, decoder + resampler flushed, then decode-and-discard up to
 *   the target, so playback lands within a few ms of the request
 *   (resampler priming is the only residual error).
 * - Gain is applied to the PCM in-place (clamped), which is what makes
 *   >100% volume boost (MX: up to 200% on HW+/SW) possible — AudioTrack
 *   volume alone caps at 1.0.
 *
 * JNI surface (all on the FFmpegEngine Kotlin object):
 *   nativeVersion()                         -> String
 *   nativeProbe(path)                       -> MediaInfo or null
 *   nativeOpen(path, audioStreamIndex, threads) -> jlong handle (0 = fail)
 *   nativeDecode(handle, out, meta)         -> samples per channel
 *   nativeSeek(handle, targetMs)            -> boolean
 *   nativeSetGain(handle, gain)
 *   nativeSetSpeed(handle, speed)
 *   nativeSetDelayMs(handle, delayMs)       -> reported-timeline offset
 *   nativeThreadCount(handle)               -> actual decoder thread_count
 *   nativeAudioCodecName(handle)            -> raw codec name of the stream
 *   nativeOutRate(handle)                   -> current resampler out rate
 *   nativeOutChannels(handle)               -> 1 or 2
 *   nativeBaseRate(handle)                  -> source sample rate
 *   nativeDurationMs(handle)                -> jlong (-1 unknown)
 *   nativeClose(handle)
 *
 * Fix-pass additions (Monitoring v0.2 P2-3/P2-4):
 * - threads: the Decoder settings "Core limit" (core_limit). Passed at
 *   open; applied as AVCodecContext.thread_count BEFORE avcodec_open2
 *   (the only point FFmpeg honours it). <= 0 = FFmpeg default.
 * - delayMs: the Audio settings audio/BT delay. The bridge adds it to
 *   every chunk timestamp it reports, so the player's master clock
 *   (which derives from those timestamps) shifts by exactly delayMs
 *   against the heard audio. Because the video renderer is slaved to
 *   that clock, this is a TRUE relative A/V delay, not a display
 *   fudge: positive delayMs = audio heard LATER than video (audio
 *   lags); negative = audio heard EARLIER (audio leads). Internal
 *   content timestamps (seek landing, stash bookkeeping) are NOT
 *   offset — only the reported timeline is.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/mem.h>
#include <libavutil/display.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GbFFmpeg", __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* Codec names in MX Player's terms (MX-COPY-SPEC §6.4 picker labels)  */
/* ------------------------------------------------------------------ */

static const char *mx_codec_name(const AVCodecParameters *par) {
    switch (par->codec_id) {
        /* video */
        case AV_CODEC_ID_H263:        return "H.263";
        case AV_CODEC_ID_H264: {
            /* MX's picker distinguishes "H.264" and "H.264 10bits". */
            if (par->format == AV_PIX_FMT_YUV420P10LE ||
                par->format == AV_PIX_FMT_YUV420P10BE ||
                par->format == AV_PIX_FMT_YUV422P10LE ||
                par->format == AV_PIX_FMT_YUV444P10LE)
                return "H.264 10bits";
            return "H.264";
        }
        case AV_CODEC_ID_HEVC: {
            if (par->format == AV_PIX_FMT_YUV420P10LE ||
                par->format == AV_PIX_FMT_YUV420P10BE ||
                par->format == AV_PIX_FMT_YUV422P10LE ||
                par->format == AV_PIX_FMT_YUV444P10LE)
                return "H.265 10bits";
            return "H.265";
        }
        case AV_CODEC_ID_MPEG1VIDEO:  return "MPEG-1";
        case AV_CODEC_ID_MPEG2VIDEO:  return "MPEG-2";
        case AV_CODEC_ID_MPEG4:       return "MPEG-4";
        case AV_CODEC_ID_MSMPEG4V1:
        case AV_CODEC_ID_MSMPEG4V2:
        case AV_CODEC_ID_MSMPEG4V3:   return "MPEG-4";
        case AV_CODEC_ID_MJPEG:       return "MJPEG";
        case AV_CODEC_ID_VP8:         return "VP8";
        case AV_CODEC_ID_VP9:         return "VP9";
        case AV_CODEC_ID_WMV3:        return "WMV3";
        case AV_CODEC_ID_VC1:         return "VC-1";
        case AV_CODEC_ID_AV1:         return "AV1";
        case AV_CODEC_ID_FLV1:        return "FLV";
        case AV_CODEC_ID_THEORA:      return "Theora";
        case AV_CODEC_ID_RV30:        return "RealVideo 3";
        case AV_CODEC_ID_RV40:        return "RealVideo 4";
        /* audio — the §6.4 HW+ picker families first */
        case AV_CODEC_ID_AAC:         return "AAC";
        case AV_CODEC_ID_ALAC:        return "ALAC";
        case AV_CODEC_ID_AMR_NB:      return "AMR NB";
        case AV_CODEC_ID_AMR_WB:      return "AMR WB";
        case AV_CODEC_ID_FLAC:        return "FLAC";
        case AV_CODEC_ID_PCM_ALAW:    return "G.711 A-law";
        case AV_CODEC_ID_PCM_MULAW:   return "G.711 \xce\xbc-law"; /* μ-law */
        case AV_CODEC_ID_GSM:
        case AV_CODEC_ID_GSM_MS:      return "GSM";
        case AV_CODEC_ID_MP1:         return "MP1";
        case AV_CODEC_ID_MP2:         return "MP2";
        case AV_CODEC_ID_MP3:         return "MP3";
        case AV_CODEC_ID_OPUS:        return "Opus";
        case AV_CODEC_ID_QCELP:       return "QCELP";
        case AV_CODEC_ID_VORBIS:      return "Vorbis";
        case AV_CODEC_ID_AC3:         return "AC3";
        case AV_CODEC_ID_EAC3:        return "EAC3";
        case AV_CODEC_ID_DTS:         return "DTS";
        case AV_CODEC_ID_TRUEHD:      return "TrueHD";
        case AV_CODEC_ID_MLP:         return "MLP";
        case AV_CODEC_ID_WMAV1:
        case AV_CODEC_ID_WMAV2:       return "WMA";
        case AV_CODEC_ID_WMAPRO:      return "WMA Pro";
        case AV_CODEC_ID_WMAVOICE:    return "WMA Voice";
        case AV_CODEC_ID_WMALOSSLESS: return "WMA Lossless";
        case AV_CODEC_ID_COOK:        return "Cook";
        case AV_CODEC_ID_APE:         return "APE";
        case AV_CODEC_ID_WAVPACK:     return "WavPack";
        case AV_CODEC_ID_ATRAC3:      return "ATRAC3";
        case AV_CODEC_ID_ATRAC3P:     return "ATRAC3+";
        /* subtitles */
        case AV_CODEC_ID_SUBRIP:      return "SubRip";
        case AV_CODEC_ID_ASS:         return "SSA/ASS";
        case AV_CODEC_ID_WEBVTT:      return "WebVTT";
        case AV_CODEC_ID_MOV_TEXT:    return "MOV text";
        case AV_CODEC_ID_DVB_SUBTITLE:return "DVB subtitle";
        case AV_CODEC_ID_DVD_SUBTITLE:return "DVD subtitle";
        case AV_CODEC_ID_HDMV_PGS_SUBTITLE: return "PGS";
        case AV_CODEC_ID_TEXT:        return "Text";
        default: break;
    }
    if (par->codec_type == AVMEDIA_TYPE_AUDIO &&
        par->codec_id >= AV_CODEC_ID_PCM_S16LE &&
        par->codec_id <= AV_CODEC_ID_PCM_MULAW)
        return "PCM";
    return avcodec_get_name(par->codec_id);
}

static const char *stream_type_name(enum AVMediaType t) {
    switch (t) {
        case AVMEDIA_TYPE_VIDEO:    return "video";
        case AVMEDIA_TYPE_AUDIO:    return "audio";
        case AVMEDIA_TYPE_SUBTITLE: return "subtitle";
        case AVMEDIA_TYPE_DATA:     return "data";
        default:                    return "unknown";
    }
}

/* ------------------------------------------------------------------ */
/* Probe                                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    char buf[96];
    snprintf(buf, sizeof(buf), "FFmpeg %s (libavformat %d.%d.%d, libavcodec %d.%d.%d)",
             av_version_info(),
             AV_VERSION_MAJOR(avformat_version()), AV_VERSION_MINOR(avformat_version()),
             AV_VERSION_MICRO(avformat_version()),
             AV_VERSION_MAJOR(avcodec_version()), AV_VERSION_MINOR(avcodec_version()),
             AV_VERSION_MICRO(avcodec_version()));
    return (*env)->NewStringUTF(env, buf);
}

/* Builds a com.ghostblade.player.MediaInfo for path, or NULL on any
 * failure (unreadable file, no streams, JNI class mismatch). Opens its
 * own format context; never touches a playback session. */
JNIEXPORT jobject JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeProbe(JNIEnv *env, jobject thiz, jstring jpath) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return NULL;
    AVFormatContext *fmt = NULL;
    jobject result = NULL;
    if (avformat_open_input(&fmt, path, NULL, NULL) < 0) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (avformat_find_stream_info(fmt, NULL) < 0) {
        avformat_close_input(&fmt);
        return NULL;
    }

    jclass streamCls = (*env)->FindClass(env, "com/ghostblade/player/StreamInfo");
    jclass infoCls   = (*env)->FindClass(env, "com/ghostblade/player/MediaInfo");
    jclass listCls   = (*env)->FindClass(env, "java/util/ArrayList");
    if (!streamCls || !infoCls || !listCls) goto done;
    /* StreamInfo(index, type, codec, codecName, language, title,
     *            bitrate, width, height, sampleRate, channels) */
    jmethodID streamCtor = (*env)->GetMethodID(env, streamCls, "<init>",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;Ljava/lang/String;JIIII)V");
    /* MediaInfo(format, formatName, durationMs, bitrate, streams) */
    jmethodID infoCtor = (*env)->GetMethodID(env, infoCls, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJLjava/util/List;)V");
    jmethodID listCtor = (*env)->GetMethodID(env, listCls, "<init>", "()V");
    jmethodID listAdd  = (*env)->GetMethodID(env, listCls, "add",
        "(Ljava/lang/Object;)Z");
    if (!streamCtor || !infoCtor || !listCtor || !listAdd) goto done;

    jobject list = (*env)->NewObject(env, listCls, listCtor);
    if (!list) goto done;

    for (unsigned i = 0; i < fmt->nb_streams; i++) {
        AVStream *st = fmt->streams[i];
        AVCodecParameters *par = st->codecpar;
        AVDictionaryEntry *lang = av_dict_get(st->metadata, "language", NULL, 0);
        AVDictionaryEntry *tit  = av_dict_get(st->metadata, "title", NULL, 0);
        jstring jtype  = (*env)->NewStringUTF(env, stream_type_name(par->codec_type));
        jstring jcodec = (*env)->NewStringUTF(env, mx_codec_name(par));
        jstring jraw   = (*env)->NewStringUTF(env, avcodec_get_name(par->codec_id));
        jstring jlang  = lang ? (*env)->NewStringUTF(env, lang->value) : NULL;
        jstring jtit   = tit  ? (*env)->NewStringUTF(env, tit->value)  : NULL;
        int channels = 0, rate = 0;
        if (par->codec_type == AVMEDIA_TYPE_AUDIO) {
            channels = par->ch_layout.nb_channels;
            rate = par->sample_rate;
        }
        jobject so = (*env)->NewObject(env, streamCls, streamCtor,
            (jint) st->index, jtype, jcodec, jraw, jlang, jtit,
            (jlong) (par->bit_rate > 0 ? par->bit_rate : 0),
            (jint) (par->codec_type == AVMEDIA_TYPE_VIDEO ? par->width : 0),
            (jint) (par->codec_type == AVMEDIA_TYPE_VIDEO ? par->height : 0),
            (jint) rate, (jint) channels);
        if (!so) goto done;
        (*env)->CallBooleanMethod(env, list, listAdd, so);
        (*env)->DeleteLocalRef(env, so);
    }

    int64_t durMs = fmt->duration == AV_NOPTS_VALUE
        ? -1 : (int64_t) (fmt->duration / 1000);
    jstring jfmt  = (*env)->NewStringUTF(env,
        fmt->iformat->long_name ? fmt->iformat->long_name : "");
    jstring jfmts = (*env)->NewStringUTF(env,
        fmt->iformat->name ? fmt->iformat->name : "");
    result = (*env)->NewObject(env, infoCls, infoCtor, jfmt, jfmts,
        (jlong) durMs,
        (jlong) (fmt->bit_rate > 0 ? fmt->bit_rate : 0), list);
done:
    avformat_close_input(&fmt);
    return result;
}

/* ------------------------------------------------------------------ */
/* Playback session                                                    */
/* ------------------------------------------------------------------ */

typedef struct {
    pthread_mutex_t lock;
    AVFormatContext *fmt;
    AVCodecContext  *dec;
    int              audio_index;
    SwrContext      *swr;
    AVPacket        *pkt;
    AVFrame         *frame;
    int              pkt_held;      /* pkt holds an unsent audio packet */
    int              flush_sent;    /* NULL (EOF) packet sent to decoder */
    int              eof;           /* decoder drained after EOF */
    int              base_rate;     /* source sample rate (Hz) */
    int              out_channels;  /* 1 or 2 */
    int              cur_in_rate;   /* rate of last decoded frame */
    int              cur_in_fmt;    /* sample format swr was built for */
    int              cur_in_ch;     /* channel count swr was built for */
    int              cur_out_rate;  /* resampler out rate (speed-adjusted) */
    float            gain;          /* linear, 1.0 = unity */
    double           speed;         /* 1.0 = normal */
    int              threads;       /* actual decoder thread_count after open */
    int64_t          delay_ms;      /* reported-timeline offset (see header) */
    int              swr_dirty;     /* recreate swr before next convert */
    int64_t          start_offset_ms; /* content origin (stream start) */
    int64_t          duration_ms;   /* -1 if unknown */
    /* Stash: one decoded+converted frame awaiting drain by Kotlin. */
    int16_t         *stash;
    int              stash_cap;     /* capacity, samples per channel */
    int              stash_len;     /* valid samples per channel */
    int              stash_pos;     /* consumed samples per channel */
    int64_t          stash_pts_ms;  /* content ms of stash sample 0 */
    /* Seek landing: discard decoded output before this content time. */
    int              skip_active;
    int64_t          skip_target_ms;
} GbSession;

static void swr_teardown(GbSession *s) {
    if (s->swr) swr_free(&s->swr);
}

/* (Re)create the resampler for a frame with the given input params.
 * Output: S16, mono/stereo matching the source, rate = in_rate/speed. */
static int swr_setup(GbSession *s, int in_rate, enum AVSampleFormat in_fmt,
                     const AVChannelLayout *in_layout) {
    swr_teardown(s);
    AVChannelLayout out_layout;
    av_channel_layout_default(&out_layout, s->out_channels);
    int out_rate = (int) lrint((double) in_rate / s->speed);
    if (out_rate < 1000) out_rate = 1000;
    int r = swr_alloc_set_opts2(&s->swr, &out_layout, AV_SAMPLE_FMT_S16,
                                out_rate, in_layout, in_fmt, in_rate, 0, NULL);
    av_channel_layout_uninit(&out_layout);
    if (r < 0 || !s->swr) return -1;
    if (swr_init(s->swr) < 0) { swr_teardown(s); return -1; }
    s->cur_in_rate = in_rate;
    s->cur_in_fmt = (int) in_fmt;
    s->cur_in_ch = in_layout->nb_channels;
    s->cur_out_rate = out_rate;
    s->swr_dirty = 0;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeOpen(JNIEnv *env, jobject thiz,
                                                   jstring jpath, jint jstream,
                                                   jint jthreads) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return 0;
    GbSession *s = (GbSession *) calloc(1, sizeof(GbSession));
    if (!s) { (*env)->ReleaseStringUTFChars(env, jpath, path); return 0; }
    pthread_mutex_init(&s->lock, NULL);
    s->gain = 1.0f;
    s->speed = 1.0;
    s->duration_ms = -1;

    if (avformat_open_input(&s->fmt, path, NULL, NULL) < 0) goto fail;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    path = NULL;
    if (avformat_find_stream_info(s->fmt, NULL) < 0) goto fail;

    int idx;
    if (jstream >= 0 && (unsigned) jstream < s->fmt->nb_streams &&
        s->fmt->streams[jstream]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
        idx = (int) jstream;
    } else {
        idx = av_find_best_stream(s->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    }
    if (idx < 0) goto fail;
    s->audio_index = idx;
    AVStream *st = s->fmt->streams[idx];
    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) goto fail;
    s->dec = avcodec_alloc_context3(codec);
    if (!s->dec) goto fail;
    if (avcodec_parameters_to_context(s->dec, st->codecpar) < 0) goto fail;
    s->dec->pkt_timebase = st->time_base;
    s->dec->request_sample_fmt = AV_SAMPLE_FMT_S16;
    /* Decoder settings "Core limit" (core_limit): thread_count must be
     * set before avcodec_open2; <= 0 leaves FFmpeg's default. */
    if (jthreads > 0) s->dec->thread_count = (int) jthreads;
    if (avcodec_open2(s->dec, codec, NULL) < 0) goto fail;
    s->threads = s->dec->thread_count;

    s->base_rate = s->dec->sample_rate;
    if (s->base_rate <= 0) goto fail;
    int nch = s->dec->ch_layout.nb_channels;
    if (nch <= 0) nch = 2;
    s->out_channels = nch >= 2 ? 2 : 1;
    s->cur_in_rate = s->base_rate;
    s->cur_out_rate = s->base_rate;

    if (st->start_time != AV_NOPTS_VALUE)
        s->start_offset_ms = av_rescale_q(st->start_time, st->time_base,
                                          AV_TIME_BASE_Q) / 1000;
    else if (s->fmt->start_time != AV_NOPTS_VALUE)
        s->start_offset_ms = s->fmt->start_time / 1000;
    if (s->fmt->duration != AV_NOPTS_VALUE)
        s->duration_ms = s->fmt->duration / 1000 - s->start_offset_ms;
    else if (st->duration != AV_NOPTS_VALUE)
        s->duration_ms = av_rescale_q(st->duration, st->time_base,
                                      AV_TIME_BASE_Q) / 1000;
    if (s->duration_ms < 0 && s->fmt->duration != AV_NOPTS_VALUE)
        s->duration_ms = s->fmt->duration / 1000;

    s->pkt = av_packet_alloc();
    s->frame = av_frame_alloc();
    if (!s->pkt || !s->frame) goto fail;
    return (jlong) (intptr_t) s;

fail:
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (s->frame) av_frame_free(&s->frame);
    if (s->pkt) av_packet_free(&s->pkt);
    swr_teardown(s);
    if (s->dec) avcodec_free_context(&s->dec);
    if (s->fmt) avformat_close_input(&s->fmt);
    pthread_mutex_destroy(&s->lock);
    free(s->stash);
    free(s);
    return 0;
}

static GbSession *session_of(jlong h) { return (GbSession *) (intptr_t) h; }

/* Convert the just-decoded frame in s->frame into the stash.
 * Returns 1 if the stash holds samples, 0 if the frame was fully
 * discarded (seek landing) or produced nothing. */
static int convert_frame(GbSession *s) {
    AVFrame *f = s->frame;
    AVStream *st = s->fmt->streams[s->audio_index];
    int in_rate = f->sample_rate > 0 ? f->sample_rate : s->base_rate;

    AVChannelLayout layout;
    if (f->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&layout, &f->ch_layout);
    } else if (s->dec->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&layout, &s->dec->ch_layout);
    } else {
        av_channel_layout_default(&layout, 2);
    }

    /* Rebuild the resampler whenever the input params (rate, sample
     * format, channel count) change mid-stream, or after a speed
     * change / seek marked it dirty. */
    if (!s->swr || s->swr_dirty || in_rate != s->cur_in_rate ||
        (int) f->format != s->cur_in_fmt ||
        layout.nb_channels != s->cur_in_ch) {
        if (swr_setup(s, in_rate, (enum AVSampleFormat) f->format, &layout) < 0) {
            av_channel_layout_uninit(&layout);
            return 0;
        }
    }
    av_channel_layout_uninit(&layout);

    int64_t best = f->best_effort_timestamp;
    int64_t fpts_ms = best == AV_NOPTS_VALUE ? 0
        : av_rescale_q(best, st->time_base, AV_TIME_BASE_Q) / 1000
          - s->start_offset_ms;

    int max_out = (int) av_rescale_rnd(
        swr_get_delay(s->swr, in_rate) + f->nb_samples,
        s->cur_out_rate, in_rate, AV_ROUND_UP) + 64;
    if (max_out > s->stash_cap) {
        int16_t *nb = (int16_t *) realloc(s->stash,
            (size_t) max_out * (size_t) s->out_channels * sizeof(int16_t));
        if (!nb) return 0;
        s->stash = nb;
        s->stash_cap = max_out;
    }
    uint8_t *outp = (uint8_t *) s->stash;
    int got = swr_convert(s->swr, &outp, max_out,
                          (const uint8_t **) f->extended_data, f->nb_samples);
    if (got < 0) return 0;
    s->stash_len = got;
    s->stash_pos = 0;
    s->stash_pts_ms = fpts_ms;

    /* Seek landing: drop output that precedes the target. */
    if (s->skip_active && got > 0) {
        int64_t frame_dur_ms = (int64_t) f->nb_samples * 1000 / in_rate;
        if (fpts_ms + frame_dur_ms <= s->skip_target_ms) {
            s->stash_len = 0;             /* whole frame before target */
            return 0;
        }
        if (fpts_ms < s->skip_target_ms) {
            int64_t drop = (s->skip_target_ms - fpts_ms) * s->cur_out_rate / 1000;
            if (drop > got) drop = got;
            s->stash_pos = (int) drop;
            s->stash_pts_ms = s->skip_target_ms -
                (int64_t) ((double) s->stash_pos * s->speed * 1000.0 / in_rate);
        }
        s->skip_active = 0;
    }

    /* Gain (volume boost path) — clamp to S16. */
    if (s->gain != 1.0f && got > 0) {
        int total = got * s->out_channels;
        for (int i = 0; i < total; i++) {
            int32_t v = (int32_t) lrintf((float) s->stash[i] * s->gain);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            s->stash[i] = (int16_t) v;
        }
    }
    return got > s->stash_pos ? 1 : 0;
}

/* Fill the stash with the next decoded frame.
 * Returns 1 = stash has samples, 0 = EOF/drained, -1 = fatal error. */
static int fill_stash(GbSession *s) {
    if (s->stash_pos < s->stash_len) return 1;
    s->stash_len = s->stash_pos = 0;
    if (s->eof) return 0;
    for (;;) {
        int r = avcodec_receive_frame(s->dec, s->frame);
        if (r == 0) {
            int c = convert_frame(s);
            av_frame_unref(s->frame);
            if (c > 0) return 1;
            continue;                 /* discarded frame: get the next */
        }
        if (r == AVERROR_EOF) { s->eof = 1; return 0; }
        if (r != AVERROR(EAGAIN)) {
            LOGE("receive_frame error %d", r);
            s->eof = 1;
            return -1;
        }
        /* Decoder wants more input. */
        if (s->flush_sent) { s->eof = 1; return 0; }
        if (!s->pkt_held) {
            int rr = av_read_frame(s->fmt, s->pkt);
            if (rr < 0) {
                avcodec_send_packet(s->dec, NULL);
                s->flush_sent = 1;
                continue;
            }
            if (s->pkt->stream_index != s->audio_index) {
                av_packet_unref(s->pkt);
                continue;
            }
            s->pkt_held = 1;
        }
        int sr = avcodec_send_packet(s->dec, s->pkt);
        if (sr >= 0) {
            av_packet_unref(s->pkt);
            s->pkt_held = 0;
        } else if (sr == AVERROR(EAGAIN)) {
            continue;                 /* loop back to receive_frame */
        } else {
            av_packet_unref(s->pkt);  /* corrupt packet: drop it */
            s->pkt_held = 0;
        }
    }
}

/* meta[0] = content ms of the first sample written (or of the next
 * sample at EOF), meta[1] = 1 when the stream is fully drained after
 * this call. Returns samples per channel written into `out`. */
JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeDecode(JNIEnv *env, jobject thiz,
                                                     jlong h, jbyteArray out,
                                                     jlongArray meta) {
    (void) thiz;
    GbSession *s = session_of(h);
    if (!s || !out) return -1;
    pthread_mutex_lock(&s->lock);
    jsize out_bytes = (*env)->GetArrayLength(env, out);
    int capacity = (int) (out_bytes / (s->out_channels * 2));
    int total = 0;
    int64_t chunk_pts = -1;
    while (total < capacity) {
        int f = fill_stash(s);
        if (f <= 0) break;
        int avail = s->stash_len - s->stash_pos;
        int take = avail < capacity - total ? avail : capacity - total;
        if (take <= 0) break;
        if (chunk_pts < 0) {
            chunk_pts = s->stash_pts_ms + (int64_t)
                ((double) s->stash_pos * s->speed * 1000.0 / s->cur_in_rate);
        }
        (*env)->SetByteArrayRegion(env, out,
            (jsize) (total * s->out_channels * 2),
            (jsize) (take * s->out_channels * 2),
            (const jbyte *) (s->stash + (size_t) s->stash_pos * s->out_channels));
        s->stash_pos += take;
        total += take;
    }
    int64_t m[2];
    /* Reported timeline carries the configured audio delay (see the
     * file header): the player's master clock derives from these
     * timestamps, so offsetting them shifts audio against the slaved
     * video by exactly delay_ms. Internal content timestamps stay
     * un-offset. */
    m[0] = (chunk_pts >= 0 ? chunk_pts
         : (s->stash_len > 0 ? s->stash_pts_ms : 0)) + s->delay_ms;
    m[1] = (s->eof && s->stash_pos >= s->stash_len) ? 1 : 0;
    (*env)->SetLongArrayRegion(env, meta, 0, 2, (const jlong *) m);
    pthread_mutex_unlock(&s->lock);
    return (jint) total;
}

JNIEXPORT jboolean JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeSeek(JNIEnv *env, jobject thiz,
                                                   jlong h, jlong target_ms) {
    (void) thiz; (void) env;
    GbSession *s = session_of(h);
    if (!s) return JNI_FALSE;
    pthread_mutex_lock(&s->lock);
    AVStream *st = s->fmt->streams[s->audio_index];
    int64_t want_ms = target_ms + s->start_offset_ms;
    if (want_ms < 0) want_ms = 0;
    int64_t ts = av_rescale_q(want_ms * 1000, AV_TIME_BASE_Q, st->time_base);
    int r = avformat_seek_file(s->fmt, s->audio_index, INT64_MIN, ts, ts,
                               AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        /* Fallback: whole-file seek in AV_TIME_BASE units. */
        r = avformat_seek_file(s->fmt, -1, INT64_MIN, want_ms * 1000,
                               want_ms * 1000, AVSEEK_FLAG_BACKWARD);
    }
    avcodec_flush_buffers(s->dec);
    swr_teardown(s);
    s->swr_dirty = 1;
    if (s->pkt_held) { av_packet_unref(s->pkt); s->pkt_held = 0; }
    s->flush_sent = 0;
    s->eof = 0;
    s->stash_len = s->stash_pos = 0;
    s->skip_active = 1;
    s->skip_target_ms = target_ms;
    pthread_mutex_unlock(&s->lock);
    return r >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeSetGain(JNIEnv *env, jobject thiz,
                                                      jlong h, jfloat gain) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (gain < 0.0f) gain = 0.0f;
    if (gain > 4.0f) gain = 4.0f;
    s->gain = gain;
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeSetSpeed(JNIEnv *env, jobject thiz,
                                                       jlong h, jfloat speed) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    double v = speed;
    if (v < 0.25) v = 0.25;
    if (v > 4.0) v = 4.0;
    if (v != s->speed) {
        s->speed = v;
        s->swr_dirty = 1;   /* resampler rebuilt with the new out rate */
    }
    pthread_mutex_unlock(&s->lock);
}

/* Audio/BT delay: offset applied to every reported chunk timestamp.
 * Positive = audio heard later than video (audio lags); negative =
 * audio heard earlier (audio leads). Clamped to ±120 s — the settings
 * UI works in seconds, and anything larger is a data error. Takes
 * effect on the next decoded chunk; the Kotlin side re-bases its
 * clock accounting by the same delta so the shift is immediate. */
JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeSetDelayMs(JNIEnv *env, jobject thiz,
                                                         jlong h, jlong delay_ms) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (delay_ms > 120000) delay_ms = 120000;
    if (delay_ms < -120000) delay_ms = -120000;
    s->delay_ms = delay_ms;
    pthread_mutex_unlock(&s->lock);
}

/* Actual decoder thread_count after open (FFmpeg may clamp the
 * requested core_limit value). 0 if the session is gone. */
JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeThreadCount(JNIEnv *env, jobject thiz,
                                                          jlong h) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    return s ? (jint) s->threads : 0;
}

/* Raw FFmpeg codec name ("ac3", "dts", …) of the opened audio stream,
 * or NULL. Lets the Kotlin side enforce the Decoder settings HW+
 * audio-codec allowlist (omx_audio_codecs) for real. Immutable after
 * open, so no lock needed (same as nativeBaseRate). */
JNIEXPORT jstring JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeAudioCodecName(JNIEnv *env, jobject thiz,
                                                             jlong h) {
    (void) thiz;
    GbSession *s = session_of(h);
    if (!s || !s->dec) return NULL;
    return (*env)->NewStringUTF(env, avcodec_get_name(s->dec->codec_id));
}

JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeOutRate(JNIEnv *env, jobject thiz,
                                                      jlong h) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    if (!s) return 0;
    pthread_mutex_lock(&s->lock);
    jint r = (jint) (int) lrint((double) s->cur_in_rate / s->speed);
    pthread_mutex_unlock(&s->lock);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeOutChannels(JNIEnv *env, jobject thiz,
                                                          jlong h) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    return s ? (jint) s->out_channels : 0;
}

JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeBaseRate(JNIEnv *env, jobject thiz,
                                                       jlong h) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    return s ? (jint) s->base_rate : 0;
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeDurationMs(JNIEnv *env, jobject thiz,
                                                         jlong h) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    return s ? (jlong) s->duration_ms : -1;
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeClose(JNIEnv *env, jobject thiz,
                                                    jlong h) {
    (void) env; (void) thiz;
    GbSession *s = session_of(h);
    if (!s) return;
    /* Caller guarantees no decode/seek is in flight. */
    if (s->frame) av_frame_free(&s->frame);
    if (s->pkt) av_packet_free(&s->pkt);
    swr_teardown(s);
    if (s->dec) avcodec_free_context(&s->dec);
    if (s->fmt) avformat_close_input(&s->fmt);
    free(s->stash);
    pthread_mutex_destroy(&s->lock);
    free(s);
}

/* ================================================================== */
/* SW VIDEO session — full software video decode (MX's SW mode).      */
/*                                                                    */
/* This is the FFPlayer half of the engine: it demuxes and decodes    */
/* the VIDEO stream in software and renders NATIVELY — frames are     */
/* converted with libswscale and written straight into an             */
/* ANativeWindow (the player's Surface) from C. No frame ever crosses */
/* into Kotlin: a Kotlin-side RGB copy would cost a full-frame copy   */
/* plus a GPU re-upload per frame and could not hold MX-grade         */
/* smoothness. The Kotlin side (SwVideoSession) owns only the SYNC    */
/* POLICY (SwFrameScheduler): audio is the master clock (the          */
/* SwAudioSession clock, exactly as in HW+), and the render thread    */
/* asks this bridge for the pending frame's pts, then tells it to     */
/* show or skip that frame.                                           */
/*                                                                    */
/* Pipeline depth is TWO decoded frames (a small ring): while the     */
/* head frame waits for its due time, the next frame is already       */
/* decoded, so decode latency does not serialise into the display     */
/* cadence. peek() tops the ring up; prefetch() exists so the         */
/* scheduler can fill the second slot during its wait window.         */
/*                                                                    */
/* Rotation: mov/mp4 display-matrix side data (ffplay's               */
/* -av_display_rotation_get convention) or the legacy "rotate"        */
/* metadata give the clockwise display rotation; 90/270 are applied   */
/* as a transpose during the blit and the DISPLAY dimensions (post-   */
/* rotation) are what Kotlin sees, so aspect/zoom math downstream     */
/* is identical to the hardware path's.                               */
/*                                                                    */
/* JNI surface (FFmpegEngine object, nativeVideo*):                   */
/*   nativeVideoOpen(path, streamIndex, threads, speedup) -> handle   */
/*   nativeVideoSetSurface(handle, Surface|null)                      */
/*   nativeVideoPeekPts(handle)   -> pending pts ms, -2 EOF, -1 error */
/*   nativeVideoPrefetch(handle)  -> top up the decode-ahead ring     */
/*   nativeVideoShow(handle)      -> consume + render the head frame  */
/*   nativeVideoSkip(handle)      -> consume + drop the head frame    */
/*   nativeVideoRepaint(handle)   -> re-render the last shown frame   */
/*   nativeVideoSeek(handle, ms)  -> keyframe seek + landing discard  */
/*   nativeVideoWidth/Height/Rotation/FrameDurMs/DurationMs(handle)   */
/*   nativeVideoDropped(handle)   -> seek discards + scheduler skips  */
/*   nativeVideoIs10Bit(handle), nativeVideoCodecName(handle)         */
/*   nativeVideoClose(handle)                                         */
/* ================================================================== */

#define GBV_PEEK_EOF   (-2)
#define GBV_PEEK_ERROR (-1)
#define GBV_SLOTS 2

typedef struct {
    pthread_mutex_t lock;
    AVFormatContext *fmt;
    AVCodecContext  *dec;
    int              video_index;
    AVPacket        *pkt;
    int              pkt_held;
    int              flush_sent;
    int              demux_eof;
    int              drained;      /* decoder returned AVERROR_EOF */
    int              hard_error;   /* fatal decode error: peek = ERROR */
    AVFrame         *slots[GBV_SLOTS];
    int64_t          slot_pts[GBV_SLOTS];
    int              slot_head;    /* oldest filled slot */
    int              slot_count;
    AVFrame         *shown;        /* last frame consumed by show() */
    struct SwsContext *sws;
    int              sws_w, sws_h, sws_fmt;
    uint8_t         *scratch;      /* coded-size RGBA (rotated blits) */
    size_t           scratch_cap;
    ANativeWindow   *window;
    int              coded_w, coded_h;
    int              disp_w, disp_h;
    int              rotation;     /* CW display rotation: 0/90/180/270 */
    int              is10bit;
    int              threads;
    int64_t          frame_dur_ms;
    int64_t          start_offset_ms;
    int64_t          duration_ms;  /* -1 unknown */
    int64_t          dropped;
    int              skip_active;  /* seek landing discard armed */
    int64_t          skip_target_ms;
} GbVideoSession;

static GbVideoSession *vsession_of(jlong h) {
    return (GbVideoSession *) (intptr_t) h;
}

static int gbv_is_10bit_fmt(int fmt) {
    switch (fmt) {
        case AV_PIX_FMT_YUV420P10LE: case AV_PIX_FMT_YUV420P10BE:
        case AV_PIX_FMT_YUV422P10LE: case AV_PIX_FMT_YUV422P10BE:
        case AV_PIX_FMT_YUV444P10LE: case AV_PIX_FMT_YUV444P10BE:
        case AV_PIX_FMT_P010LE:      case AV_PIX_FMT_P010BE:
            return 1;
        default: return 0;
    }
}

/* Clockwise display rotation in degrees, snapped to a right angle.
 * Display matrix wins; the mov "rotate" metadata is the fallback
 * (it is already expressed as clockwise display degrees). */
static int gbv_rotation(const AVStream *st) {
    const AVCodecParameters *par = st->codecpar;
    const AVPacketSideData *sd = av_packet_side_data_get(
        par->coded_side_data, par->nb_coded_side_data,
        AV_PKT_DATA_DISPLAYMATRIX);
    if (sd && sd->size >= 9 * sizeof(int32_t)) {
        /* ffplay convention: the correction is the negated matrix
         * angle, normalised to [0, 360). */
        double deg = -av_display_rotation_get((const int32_t *) sd->data);
        while (deg < 0) deg += 360.0;
        while (deg >= 360.0) deg -= 360.0;
        int snapped = (int) lrint(deg / 90.0) * 90;
        return snapped % 360;
    }
    AVDictionaryEntry *r = av_dict_get(st->metadata, "rotate", NULL, 0);
    if (r) {
        int v = atoi(r->value) % 360;
        if (v < 0) v += 360;
        return (int) lrint(v / 90.0) * 90 % 360;
    }
    return 0;
}

static int64_t gbv_frame_pts_ms(GbVideoSession *s, const AVFrame *f) {
    AVStream *st = s->fmt->streams[s->video_index];
    int64_t best = f->best_effort_timestamp;
    if (best == AV_NOPTS_VALUE) return 0;
    return av_rescale_q(best, st->time_base, AV_TIME_BASE_Q) / 1000
           - s->start_offset_ms;
}

/* Decode forward until a slot is filled or the stream drains.
 * Applies the seek-landing discard. Returns 1 = at least one slot
 * filled, 0 = drained, -1 = hard error. Caller holds the lock. */
static int gbv_fill(GbVideoSession *s) {
    if (s->slot_count > 0) {
        /* Top up the second slot opportunistically; the return value
         * only reports whether the HEAD is ready. */
    }
    while (s->slot_count < GBV_SLOTS && !s->drained && !s->hard_error) {
        AVFrame *slot = s->slots[(s->slot_head + s->slot_count) % GBV_SLOTS];
        int r = avcodec_receive_frame(s->dec, slot);
        if (r == 0) {
            int64_t pts = gbv_frame_pts_ms(s, slot);
            if (s->skip_active) {
                int64_t dur = s->frame_dur_ms > 0 ? s->frame_dur_ms : 33;
                if (pts + dur <= s->skip_target_ms) {
                    /* Frame ends at or before the target: not the
                     * frame that covers it — discard and continue. */
                    av_frame_unref(slot);
                    s->dropped++;
                    continue;
                }
                s->skip_active = 0;   /* this frame covers the target */
            }
            s->slot_pts[(s->slot_head + s->slot_count) % GBV_SLOTS] = pts;
            s->slot_count++;
            continue;
        }
        if (r == AVERROR_EOF) { s->drained = 1; break; }
        if (r != AVERROR(EAGAIN)) {
            LOGE("video receive_frame error %d", r);
            s->hard_error = 1;
            break;
        }
        /* Decoder wants more input. */
        if (s->flush_sent) { s->drained = 1; break; }
        if (!s->pkt_held) {
            if (s->demux_eof) {
                avcodec_send_packet(s->dec, NULL);
                s->flush_sent = 1;
                continue;
            }
            int rr = av_read_frame(s->fmt, s->pkt);
            if (rr < 0) {
                s->demux_eof = 1;
                continue;
            }
            if (s->pkt->stream_index != s->video_index) {
                av_packet_unref(s->pkt);
                continue;
            }
            s->pkt_held = 1;
        }
        int sr = avcodec_send_packet(s->dec, s->pkt);
        if (sr >= 0) {
            av_packet_unref(s->pkt);
            s->pkt_held = 0;
        } else if (sr == AVERROR(EAGAIN)) {
            continue;
        } else {
            av_packet_unref(s->pkt);  /* corrupt packet: drop it */
            s->pkt_held = 0;
        }
    }
    if (s->slot_count > 0) return 1;
    return s->hard_error ? -1 : 0;
}

/* (Re)build the scaler for this frame's format/size, with the
 * frame's colour metadata applied (swscale otherwise assumes
 * BT.601 limited range for everything — visibly wrong on HD). */
static int gbv_sws_for(GbVideoSession *s, const AVFrame *f) {
    if (s->sws && s->sws_w == f->width && s->sws_h == f->height &&
        s->sws_fmt == f->format)
        return 0;
    s->sws = sws_getCachedContext(s->sws, f->width, f->height,
        (enum AVPixelFormat) f->format, f->width, f->height,
        AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
    if (!s->sws) return -1;
    int cs = SWS_CS_ITU709;
    switch (f->colorspace) {
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M: cs = SWS_CS_ITU601; break;
        case AVCOL_SPC_SMPTE240M: cs = SWS_CS_SMPTE240M; break;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: cs = SWS_CS_BT2020; break;
        default: break;   /* BT709 + unspecified-HD default */
    }
    int src_range = f->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    sws_setColorspaceDetails(s->sws, sws_getCoefficients(cs), src_range,
                             sws_getCoefficients(cs), 1, 0, 1 << 16, 1 << 16);
    s->sws_w = f->width;
    s->sws_h = f->height;
    s->sws_fmt = f->format;
    return 0;
}

/* Convert + post `f` to the attached window (if any), applying the
 * display rotation. Caller holds the lock and has already consumed
 * the frame out of the ring. */
static void gbv_blit(GbVideoSession *s, AVFrame *f) {
    if (!s->window || !f || f->width <= 0 || f->height <= 0) return;
    if (gbv_sws_for(s, f) < 0) return;
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(s->window, &buf, NULL) < 0) return;
    const int W = buf.width, H = buf.height;
    uint8_t *dst = (uint8_t *) buf.bits;
    const int dst_stride_px = buf.stride;
    if (s->rotation == 0 && W == f->width && H == f->height) {
        /* Fast path: scale straight into the window buffer — the
         * frame is written exactly once, no intermediate copy. */
        uint8_t *dplane[4] = { dst, NULL, NULL, NULL };
        int dstride[4] = { dst_stride_px * 4, 0, 0, 0 };
        sws_scale(s->sws, (const uint8_t * const *) f->data, f->linesize,
                  0, f->height, dplane, dstride);
    } else {
        size_t need = (size_t) f->width * (size_t) f->height * 4;
        if (need > s->scratch_cap) {
            uint8_t *nb = (uint8_t *) realloc(s->scratch, need);
            if (!nb) { ANativeWindow_unlockAndPost(s->window); return; }
            s->scratch = nb;
            s->scratch_cap = need;
        }
        uint8_t *dplane[4] = { s->scratch, NULL, NULL, NULL };
        int dstride[4] = { f->width * 4, 0, 0, 0 };
        sws_scale(s->sws, (const uint8_t * const *) f->data, f->linesize,
                  0, f->height, dplane, dstride);
        const int w = f->width, h = f->height;
        const uint32_t *src = (const uint32_t *) s->scratch;
        for (int y = 0; y < H; y++) {
            uint32_t *drow = (uint32_t *) (dst + (size_t) y * dst_stride_px * 4);
            for (int x = 0; x < W; x++) {
                uint32_t px;
                switch (s->rotation) {
                    case 90:  px = src[(size_t) (h - 1 - x) * w + y]; break;
                    case 180: px = src[(size_t) (h - 1 - y) * w + (w - 1 - x)]; break;
                    case 270: px = src[(size_t) x * w + (w - 1 - y)]; break;
                    default:  px = (x < w && y < h)
                                   ? src[(size_t) y * w + x] : 0xFF000000u; break;
                }
                drow[x] = px;
            }
        }
    }
    ANativeWindow_unlockAndPost(s->window);
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoOpen(JNIEnv *env, jobject thiz,
                                                        jstring jpath, jint jstream,
                                                        jint jthreads, jboolean speedup) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return 0;
    GbVideoSession *s = (GbVideoSession *) calloc(1, sizeof(GbVideoSession));
    if (!s) { (*env)->ReleaseStringUTFChars(env, jpath, path); return 0; }
    pthread_mutex_init(&s->lock, NULL);
    s->duration_ms = -1;

    if (avformat_open_input(&s->fmt, path, NULL, NULL) < 0) goto fail;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    path = NULL;
    if (avformat_find_stream_info(s->fmt, NULL) < 0) goto fail;

    int idx;
    if (jstream >= 0 && (unsigned) jstream < s->fmt->nb_streams &&
        s->fmt->streams[jstream]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
        idx = (int) jstream;
    } else {
        idx = av_find_best_stream(s->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    }
    if (idx < 0) goto fail;
    s->video_index = idx;
    AVStream *st = s->fmt->streams[idx];
    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) goto fail;
    s->dec = avcodec_alloc_context3(codec);
    if (!s->dec) goto fail;
    if (avcodec_parameters_to_context(s->dec, st->codecpar) < 0) goto fail;
    s->dec->pkt_timebase = st->time_base;
    /* Decoder settings "Core limit": thread_count before open2. */
    if (jthreads > 0) s->dec->thread_count = (int) jthreads;
    /* "Use speedup tricks" (MX §6): skip the in-loop deblocking
     * filter in the SW decoder — a real, documented speed/quality
     * trade, applied before open2 like MX's OMX toggle. */
    if (speedup) s->dec->skip_loop_filter = AVDISCARD_ALL;
    if (avcodec_open2(s->dec, codec, NULL) < 0) goto fail;
    s->threads = s->dec->thread_count;

    s->coded_w = s->dec->width > 0 ? s->dec->width : st->codecpar->width;
    s->coded_h = s->dec->height > 0 ? s->dec->height : st->codecpar->height;
    if (s->coded_w <= 0 || s->coded_h <= 0) goto fail;
    s->rotation = gbv_rotation(st);
    if (s->rotation == 90 || s->rotation == 270) {
        s->disp_w = s->coded_h;
        s->disp_h = s->coded_w;
    } else {
        s->disp_w = s->coded_w;
        s->disp_h = s->coded_h;
    }
    s->is10bit = gbv_is_10bit_fmt(st->codecpar->format);

    AVRational fr = st->avg_frame_rate;
    if (fr.num <= 0 || fr.den <= 0) fr = st->r_frame_rate;
    if (fr.num > 0 && fr.den > 0) {
        double fps = av_q2d(fr);
        s->frame_dur_ms = fps > 0 ? (int64_t) lrint(1000.0 / fps) : 33;
    } else {
        s->frame_dur_ms = 33;
    }
    if (s->frame_dur_ms < 1) s->frame_dur_ms = 1;

    if (st->start_time != AV_NOPTS_VALUE)
        s->start_offset_ms = av_rescale_q(st->start_time, st->time_base,
                                          AV_TIME_BASE_Q) / 1000;
    else if (s->fmt->start_time != AV_NOPTS_VALUE)
        s->start_offset_ms = s->fmt->start_time / 1000;
    if (s->fmt->duration != AV_NOPTS_VALUE)
        s->duration_ms = s->fmt->duration / 1000 - s->start_offset_ms;
    else if (st->duration != AV_NOPTS_VALUE)
        s->duration_ms = av_rescale_q(st->duration, st->time_base,
                                      AV_TIME_BASE_Q) / 1000;

    s->pkt = av_packet_alloc();
    s->shown = av_frame_alloc();
    for (int i = 0; i < GBV_SLOTS; i++) s->slots[i] = av_frame_alloc();
    if (!s->pkt || !s->shown || !s->slots[0] || !s->slots[1]) goto fail;
    return (jlong) (intptr_t) s;

fail:
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    for (int i = 0; i < GBV_SLOTS; i++)
        if (s->slots[i]) av_frame_free(&s->slots[i]);
    if (s->shown) av_frame_free(&s->shown);
    if (s->pkt) av_packet_free(&s->pkt);
    if (s->sws) sws_freeContext(s->sws);
    if (s->dec) avcodec_free_context(&s->dec);
    if (s->fmt) avformat_close_input(&s->fmt);
    pthread_mutex_destroy(&s->lock);
    free(s->scratch);
    free(s);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoSetSurface(JNIEnv *env, jobject thiz,
                                                              jlong h, jobject surface) {
    (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (s->window) {
        ANativeWindow_release(s->window);
        s->window = NULL;
    }
    if (surface) {
        s->window = ANativeWindow_fromSurface(env, surface);
        if (s->window && s->disp_w > 0 && s->disp_h > 0) {
            ANativeWindow_setBuffersGeometry(s->window, s->disp_w, s->disp_h,
                                             WINDOW_FORMAT_RGBA_8888);
        }
    }
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoPeekPts(JNIEnv *env, jobject thiz,
                                                           jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return GBV_PEEK_ERROR;
    pthread_mutex_lock(&s->lock);
    int f = gbv_fill(s);
    jlong r;
    if (s->slot_count > 0) r = (jlong) s->slot_pts[s->slot_head];
    else r = f < 0 ? GBV_PEEK_ERROR : GBV_PEEK_EOF;
    pthread_mutex_unlock(&s->lock);
    return r;
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoPrefetch(JNIEnv *env, jobject thiz,
                                                            jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    gbv_fill(s);
    pthread_mutex_unlock(&s->lock);
}

/* Consume the head slot into `shown` (replacing the previous shown
 * frame). Returns the consumed frame or NULL. Caller holds the lock. */
static AVFrame *gbv_consume_head(GbVideoSession *s) {
    if (s->slot_count <= 0) return NULL;
    AVFrame *slot = s->slots[s->slot_head];
    av_frame_unref(s->shown);
    av_frame_move_ref(s->shown, slot);
    s->slot_head = (s->slot_head + 1) % GBV_SLOTS;
    s->slot_count--;
    return s->shown;
}

JNIEXPORT jboolean JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoShow(JNIEnv *env, jobject thiz,
                                                        jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return JNI_FALSE;
    pthread_mutex_lock(&s->lock);
    AVFrame *f = gbv_consume_head(s);
    if (f) gbv_blit(s, f);
    pthread_mutex_unlock(&s->lock);
    return f ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoSkip(JNIEnv *env, jobject thiz,
                                                        jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (s->slot_count > 0) {
        av_frame_unref(s->slots[s->slot_head]);
        s->slot_head = (s->slot_head + 1) % GBV_SLOTS;
        s->slot_count--;
        s->dropped++;
    }
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT jboolean JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoRepaint(JNIEnv *env, jobject thiz,
                                                           jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return JNI_FALSE;
    pthread_mutex_lock(&s->lock);
    int ok = s->window && s->shown && s->shown->width > 0;
    if (ok) gbv_blit(s, s->shown);
    pthread_mutex_unlock(&s->lock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoSeek(JNIEnv *env, jobject thiz,
                                                        jlong h, jlong target_ms) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return JNI_FALSE;
    pthread_mutex_lock(&s->lock);
    AVStream *st = s->fmt->streams[s->video_index];
    int64_t want_ms = target_ms + s->start_offset_ms;
    if (want_ms < 0) want_ms = 0;
    int64_t ts = av_rescale_q(want_ms * 1000, AV_TIME_BASE_Q, st->time_base);
    int r = avformat_seek_file(s->fmt, s->video_index, INT64_MIN, ts, ts,
                               AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        r = avformat_seek_file(s->fmt, -1, INT64_MIN, want_ms * 1000,
                               want_ms * 1000, AVSEEK_FLAG_BACKWARD);
    }
    avcodec_flush_buffers(s->dec);
    if (s->pkt_held) { av_packet_unref(s->pkt); s->pkt_held = 0; }
    s->flush_sent = 0;
    s->demux_eof = 0;
    s->drained = 0;
    s->hard_error = 0;
    for (int i = 0; i < GBV_SLOTS; i++) av_frame_unref(s->slots[i]);
    s->slot_head = 0;
    s->slot_count = 0;
    /* `shown` deliberately survives: the pre-seek frame stays on
     * screen until the seek's covering frame is shown — no black
     * flash mid-seek, exactly like MX. */
    s->skip_active = 1;
    s->skip_target_ms = target_ms;
    pthread_mutex_unlock(&s->lock);
    return r >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoWidth(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    return s ? (jint) s->disp_w : 0;
}

JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoHeight(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    return s ? (jint) s->disp_h : 0;
}

JNIEXPORT jint JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoRotation(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    return s ? (jint) s->rotation : 0;
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoFrameDurMs(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    return s ? (jlong) s->frame_dur_ms : 0;
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoDurationMs(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    return s ? (jlong) s->duration_ms : -1;
}

JNIEXPORT jlong JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoDropped(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return 0;
    pthread_mutex_lock(&s->lock);
    jlong d = (jlong) s->dropped;
    pthread_mutex_unlock(&s->lock);
    return d;
}

JNIEXPORT jboolean JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoIs10Bit(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    return s && s->is10bit ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoCodecName(JNIEnv *env, jobject thiz, jlong h) {
    (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s || !s->dec) return NULL;
    return (*env)->NewStringUTF(env, avcodec_get_name(s->dec->codec_id));
}

JNIEXPORT void JNICALL
Java_com_ghostblade_player_FFmpegEngine_nativeVideoClose(JNIEnv *env, jobject thiz, jlong h) {
    (void) env; (void) thiz;
    GbVideoSession *s = vsession_of(h);
    if (!s) return;
    /* Caller guarantees no peek/show/seek is in flight. */
    if (s->window) ANativeWindow_release(s->window);
    for (int i = 0; i < GBV_SLOTS; i++)
        if (s->slots[i]) av_frame_free(&s->slots[i]);
    if (s->shown) av_frame_free(&s->shown);
    if (s->pkt) av_packet_free(&s->pkt);
    if (s->sws) sws_freeContext(s->sws);
    if (s->dec) avcodec_free_context(&s->dec);
    if (s->fmt) avformat_close_input(&s->fmt);
    pthread_mutex_destroy(&s->lock);
    free(s->scratch);
    free(s);
}
