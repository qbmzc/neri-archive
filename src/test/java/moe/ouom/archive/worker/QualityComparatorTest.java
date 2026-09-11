package moe.ouom.archive.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static moe.ouom.archive.worker.QualityComparator.*;

class QualityComparatorTest {
    static QualityComparator.Profile p(String codec,int rate,int bits,int bitrate) {
        return QualityComparator.profile(codec,rate,bits,bitrate);
    }

    @Test void losslessAlwaysOutranksLossyRegardlessOfBitrate() {
        assertTrue(compare(p("flac",44100,16,900000),p("mp3",44100,0,320000))>0);
        assertTrue(compare(p("mp3",44100,0,320000),p("flac",44100,16,900000))<0);
        // 假无损（有损转码后标记为无损）在本层无法区分，选中它要靠回收站兜底，不是靠这里。
        assertTrue(compare(p("flac",44100,16,700000),p("mp3",44100,0,320000))>0);
    }

    @Test void alacIsLosslessButAacIsNotDespiteTheSharedM4aExtension() {
        // MediaFiles.probe 把 alac 与 aac 都映射为 m4a，只看扩展名会把 ALAC 误判为有损。
        assertTrue(isLossless(p("m4a",44100,16,900000)));
        assertFalse(isLossless(p("m4a",44100,0,256000)));
        assertTrue(compare(p("m4a",44100,16,900000),p("mp3",44100,0,320000))>0);
        assertTrue(compare(p("m4a",44100,0,256000),p("mp3",44100,0,320000))<0);
    }

    @Test void losslessOrderingUsesSampleRateThenBitsThenBitrate() {
        assertTrue(compare(p("flac",96000,24,900000),p("flac",44100,16,900000))>0);
        assertTrue(compare(p("flac",44100,24,900000),p("flac",44100,16,900000))>0);
        assertTrue(compare(p("flac",44100,16,950000),p("flac",44100,16,900000))>0);
        assertEquals(0,compare(p("flac",44100,16,900000),p("flac",44100,16,900000)));
    }

    @Test void lossyOrderingUsesBitrateFirstSoAacDoesNotBeatHigherBitrateMp3() {
        // 加权和会在这里出错：AAC 256 被判为远好于 MP3 320。
        assertTrue(compare(p("mp3",44100,0,320000),p("aac",44100,0,256000))>0);
        // 码率相同时才用编码类别决胜。
        assertTrue(compare(p("aac",44100,0,320000),p("mp3",44100,0,320000))>0);
    }

    @Test void downgradeIsNeverSignificant() {
        assertFalse(significantlyBetter(p("mp3",44100,0,256000),p("mp3",44100,0,320000)));
        assertFalse(significantlyBetter(p("mp3",44100,0,320000),p("flac",44100,16,900000)));
        assertFalse(significantlyBetter(p("flac",44100,16,900000),p("flac",44100,16,900000)));
    }

    @Test void lossyToLosslessIsAlwaysSignificant() {
        assertTrue(significantlyBetter(p("flac",44100,16,900000),p("mp3",44100,0,320000)));
        assertTrue(significantlyBetter(p("m4a",44100,16,900000),p("mp3",44100,0,320000)));
    }

    @Test void losslessToLosslessNeedsHigherSampleRateOrBitDepth() {
        assertTrue(significantlyBetter(p("flac",96000,24,900000),p("flac",44100,16,900000)));
        assertTrue(significantlyBetter(p("flac",44100,24,900000),p("flac",44100,16,900000)));
        // 同为 16/44.1，只是 FLAC 压缩率不同，不值得替换。
        assertFalse(significantlyBetter(p("flac",44100,16,950000),p("flac",44100,16,900000)));
    }

    @Test void lossyToLossyNeedsBothRatioAndAbsoluteGain() {
        assertTrue(significantlyBetter(p("mp3",44100,0,320000),p("mp3",44100,0,192000)));
        // 比值刚好达标，但绝对增量只有 16kbps。
        assertFalse(significantlyBetter(p("mp3",44100,0,80000),p("mp3",44100,0,64000)));
        // 绝对增量达标，但比值不足 1.25。
        assertFalse(significantlyBetter(p("mp3",44100,0,364000),p("mp3",44100,0,300000)));
        // 同档位不替换。
        assertFalse(significantlyBetter(p("mp3",44100,0,320000),p("mp3",44100,0,320000)));
    }

    @Test void storedProfileIsSkippedWhenSpecsAreMissing() {
        assertNull(QualityComparator.stored("Artist/Album/Song [1]-abc.flac",0,0,0));
        assertNull(QualityComparator.stored("",44100,16,900000));
        assertNull(QualityComparator.stored("Artist/Album/Song [1]-abc",44100,16,900000));
        var stored=QualityComparator.stored("Artist\\Album\\Song [1]-abc.flac",44100,16,900000);
        assertNotNull(stored);
        assertEquals("flac",stored.codec());
        assertEquals(44100,stored.sampleRate());
    }
}
