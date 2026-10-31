public class Search {
    public static int indexOf(int[] sorted, int target) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (sorted[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return sorted[lo] == target ? lo : -1;
    }
}
