package moe.ouom.archive.worker;

import java.util.Locale;
import java.util.Objects;

/**
 * 音质比较：先分无损/有损，再在同类内逐级比较。
 *
 * <p>这里有意不使用「编码权重 + 码率」的加权和。加权和会让编码权重压倒码率的整个区间，
 * 得出「AAC 256 远好于 MP3 320」这类结论；而先分无损/有损、再在同类内比较，
 * 得到的排序可解释、可测试。
 */
public final class QualityComparator {
    /** 有损→有损认定为「显著提升」所需的最小码率倍数。 */
    public static final double DEFAULT_LOSSY_RATIO=1.25;
    /** 有损→有损认定为「显著提升」所需的最小绝对码率增量。 */
    public static final int MIN_LOSSY_BITRATE_GAIN=64_000;

    private QualityComparator() {}

    /** 一份可比较的音质档案。{@code codec} 是文件扩展名。 */
    public record Profile(String codec,int sampleRate,int bits,int bitrate) {}

    public static Profile profile(MediaFiles.Probe probe) {
        return profile(probe.extension(),probe.rate(),probe.bits(),probe.bitrate());
    }

    public static Profile profile(String extension,int sampleRate,int bits,int bitrate) {
        return new Profile(Objects.toString(extension,"").toLowerCase(Locale.ROOT),sampleRate,bits,bitrate);
    }

    /**
     * 用 songs 行里已存的探测结果构造档案，避免为已有文件重复调用 ffprobe。
     * 采样规格缺失时返回 null，调用方应退回实际探测。
     */
    public static Profile stored(String path,int sampleRate,int bits,int bitrate) {
        if(sampleRate<=0&&bits<=0&&bitrate<=0) return null;
        String extension=extensionOf(path);
        if(extension.isBlank()) return null;
        return new Profile(extension,sampleRate,bits,bitrate);
    }

    public static String extensionOf(String path) {
        String value=Objects.toString(path,"");
        int separator=Math.max(value.lastIndexOf('/'),value.lastIndexOf('\\'));
        String name=separator>=0?value.substring(separator+1):value;
        int dot=name.lastIndexOf('.');
        return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
    }

    /**
     * 无损判定只依据实测规格，不依据平台档位标签。
     *
     * <p>必须带上 {@code bits} 判断：{@link MediaFiles#probe} 把 ALAC 与 AAC 都映射为
     * {@code m4a} 扩展名，只看扩展名会把 ALAC 误判为有损。
     */
    public static boolean isLossless(Profile profile) {
        return switch(profile.codec()) {
            case "flac","alac","wav","ape","aiff","aif","dsf","dff" -> true;
            case "m4a" -> profile.bits()>=16;
            default -> false;
        };
    }

    /** 有损编码的偏好序，仅在码率相同时作决胜。 */
    public static int lossyRank(String codec) {
        return switch(Objects.toString(codec,"")) {
            case "aac","m4a" -> 3;
            case "opus","ogg","vorbis" -> 2;
            case "mp3" -> 1;
            default -> 1;
        };
    }

    /** 正数表示 a 更好，负数表示 b 更好，0 表示等价。 */
    public static int compare(Profile a,Profile b) {
        boolean losslessA=isLossless(a),losslessB=isLossless(b);
        if(losslessA!=losslessB) return losslessA?1:-1;
        if(losslessA) {
            int comparison=Integer.compare(a.sampleRate(),b.sampleRate());
            if(comparison!=0) return comparison;
            comparison=Integer.compare(a.bits(),b.bits());
            if(comparison!=0) return comparison;
            return Integer.compare(a.bitrate(),b.bitrate());
        }
        int comparison=Integer.compare(a.bitrate(),b.bitrate());
        if(comparison!=0) return comparison;
        comparison=Integer.compare(lossyRank(a.codec()),lossyRank(b.codec()));
        if(comparison!=0) return comparison;
        return Integer.compare(a.sampleRate(),b.sampleRate());
    }

    /**
     * a 相对 b 是否构成「显著提升」。门槛用于避免 320 ↔ 256 这类小差距反复替换。
     */
    public static boolean significantlyBetter(Profile a,Profile b,double lossyRatio) {
        boolean losslessA=isLossless(a),losslessB=isLossless(b);
        if(losslessA&&!losslessB) return true;
        if(!losslessA&&losslessB) return false;
        if(losslessA) return a.sampleRate()>b.sampleRate()||a.bits()>b.bits();
        if(a.bitrate()<=0||b.bitrate()<=0) return compare(a,b)>0;
        return a.bitrate()>=b.bitrate()*lossyRatio&&a.bitrate()-b.bitrate()>=MIN_LOSSY_BITRATE_GAIN;
    }

    public static boolean significantlyBetter(Profile a,Profile b) {
        return significantlyBetter(a,b,DEFAULT_LOSSY_RATIO);
    }
}
