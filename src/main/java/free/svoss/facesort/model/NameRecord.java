package free.svoss.facesort.model;

import java.util.Objects;

/**
 * Represents a person name in the database, together with the average
 * embedding of all faces currently tagged with it.
 */
public final class NameRecord {

    private final long id;
    private final String name;
    private final float[] averageEmbedding;
    private final int faceCount;

    /**
     * Creates a name record with face count defaulting to zero.
     *
     * @param id               database id
     * @param name             display name (unique in the database)
     * @param averageEmbedding average embedding of the name's faces, or null
     *                         when not yet computed
     */
    public NameRecord(long id, String name, float[] averageEmbedding) {
        this(id, name, averageEmbedding, 0);
    }

    /**
     * Creates a name record with an explicit face count.
     *
     * @param id               database id
     * @param name             display name (unique in the database)
     * @param averageEmbedding average embedding of the name's faces, or null
     *                         when not yet computed
     * @param faceCount        number of faces currently tagged with this name
     */
    public NameRecord(long id, String name, float[] averageEmbedding, int faceCount) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "name");
        this.averageEmbedding = averageEmbedding;
        this.faceCount = faceCount;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Average embedding of the name's faces, or null when not computed. */
    public float[] averageEmbedding() {
        return averageEmbedding;
    }

    /** Number of faces currently tagged with this name. */
    public int faceCount() {
        return faceCount;
    }

    @Override
    public String toString() {
        return "NameRecord{id=" + id + ", name='" + name + "'}";
    }
}