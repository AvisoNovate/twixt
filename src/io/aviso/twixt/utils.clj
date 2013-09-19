(ns io.aviso.twixt.utils
  "Some re-usable utilities. This namespace should be considered unsupported.")

(defn transform-values 
  "Transforms a map by passing each value through a provided function."
  [m f]
  (into {} (map (fn [[k v]] [k (f v)]) m)))

(declare merge-maps-recursively)

(defn- merge-values [l r]
  (cond
    ;; We know how to merge two maps together:
    (and (map? l) (map? r)) (merge-maps-recursively l r)
    ;; But we can't merge a map with a non-map
    (or (map? l) (map? r)) (throw (IllegalArgumentException. (format "Unable to merge %s with %s" l r)))
    ;; We don't try to merge seqs
    ;; In any other case the right (later) value replaces the left (earlier) value
    :else r))

(defn merge-maps-recursively 
  "Merges any number of maps together, recursively. When merging values:
  - two maps are merged, recursively
  - one map and one non-map is an error
  - otherwise, the 'right' value overwrites the 'left' value"
  [& maps]
  (apply merge-with merge-values maps))