package free.svoss.facesort.model;

/**
 * An unordered pair of name ids from the {@code not_dupes} table.
 *
 * <p>The ids are normalized so the smaller id is always stored first, matching
 * how {@code NotDupeDao} persists pairs; consequently {@code equals} treats the
 * two orders as the same pair.</p>
 *
 * @param a a name id (the smaller of the two)
 * @param b a name id (the larger of the two)
 */
public record NamePair(long a, long b) {

    public NamePair {
        if (a > b) {
            long tmp = a;
            a = b;
            b = tmp;
        }
    }
}