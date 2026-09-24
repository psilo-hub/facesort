package free.svoss.facesort.service;

import free.svoss.facesort.model.FaceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FaceSelector}: averaging the embeddings of a set of faces
 * and picking the face closest to an average embedding.
 */
class FaceSelectorTest {

    private final FaceSelector selector =
            new FaceSelector(new FaceAiService(new FakeFaceAiEngine()));

    private static FaceRecord face(String imageHash, float[] embedding) {
        return new FaceRecord(0, imageHash, 0, 0, 80, 80, 0.9, embedding, new byte[]{1}, null);
    }

    @Test
    void averageOf_computesComponentWiseMean() {
        float[] average = selector.averageOf(List.of(
                face("a", new float[]{1, 1, 0, 0, 0, 0, 0, 0}),
                face("b", new float[]{0, 1, 2, 0, 0, 0, 0, 0}),
                face("c", new float[]{2, 1, 1, 0, 0, 0, 0, 0})));

        assertArrayEquals(new float[]{1, 1, 1, 0, 0, 0, 0, 0}, average, 1e-6f);
    }

    @Test
    void mostSimilarTo_picksFaceClosestToGivenAverage() {
        List<FaceRecord> faces = List.of(
                face("near", new float[]{0.9f, 0.1f, 0, 0, 0, 0, 0, 0}),
                face("far", new float[]{0.1f, 0.9f, 0, 0, 0, 0, 0, 0}));

        FaceRecord representative = selector.mostSimilarTo(
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, faces);

        assertEquals("near", representative.imageHash());
    }

    @Test
    void mostSimilarTo_tieKeepsFirstFace() {
        List<FaceRecord> faces = List.of(
                face("first", new float[]{1, 0, 0, 0, 0, 0, 0, 0}),
                face("second", new float[]{1, 0, 0, 0, 0, 0, 0, 0}));

        FaceRecord representative = selector.mostSimilarTo(
                new float[]{1, 0, 0, 0, 0, 0, 0, 0}, faces);

        assertEquals("first", representative.imageHash(),
                "a tie on similarity must keep the first face, like every caller relied on");
    }

    @Test
    void representativeOf_usesOwnAverageForArgmax() {
        List<FaceRecord> faces = List.of(
                face("outlier", new float[]{1, 0, 0, 0, 0, 0, 0, 0}),
                face("mainA", new float[]{0.95f, 0.05f, 0, 0, 0, 0, 0, 0}),
                face("mainB", new float[]{0.95f, 0.05f, 0, 0, 0, 0, 0, 0}),
                face("other", new float[]{0, 1, 0, 0, 0, 0, 0, 0}));

        FaceRecord representative = selector.representativeOf(faces);

        assertTrue(representative.imageHash().startsWith("main"),
                "the representative must come from the majority cluster");
    }
}