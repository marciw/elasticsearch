---
navigation_title: "Cartesian-centroid"
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-metrics-cartesian-centroid-aggregation.html
---

# Cartesian-centroid aggregation [search-aggregations-metrics-cartesian-centroid-aggregation]


A metric aggregation that computes the weighted [centroid](https://en.wikipedia.org/wiki/Centroid) from all coordinate values for point and shape fields.

Example:

```console
PUT /museums
{
  "mappings": {
    "properties": {
      "location": {
        "type": "point"
      }
    }
  }
}

POST /museums/_bulk?refresh
{"index":{"_id":1}}
{"location": "POINT (491.2350 5237.4081)", "city": "Amsterdam", "name": "NEMO Science Museum"}
{"index":{"_id":2}}
{"location": "POINT (490.1618 5236.9219)", "city": "Amsterdam", "name": "Museum Het Rembrandthuis"}
{"index":{"_id":3}}
{"location": "POINT (491.4722 5237.1667)", "city": "Amsterdam", "name": "Nederlands Scheepvaartmuseum"}
{"index":{"_id":4}}
{"location": "POINT (440.5200 5122.2900)", "city": "Antwerp", "name": "Letterenhuis"}
{"index":{"_id":5}}
{"location": "POINT (233.6389 4886.1111)", "city": "Paris", "name": "Musée du Louvre"}
{"index":{"_id":6}}
{"location": "POINT (232.7000 4886.0000)", "city": "Paris", "name": "Musée d'Orsay"}

POST /museums/_search?size=0
{
  "aggs": {
    "centroid": {
      "cartesian_centroid": {
        "field": "location" <1>
      }
    }
  }
}
```

1. The `cartesian_centroid` aggregation specifies the field to use for computing the centroid, which must be a [Point](/reference/elasticsearch/mapping-reference/point.md) or a [Shape](/reference/elasticsearch/mapping-reference/shape.md) type.


The above aggregation demonstrates how one would compute the centroid of the location field for all museums' documents.

The response for the above aggregation:

```console-result
{
  ...
  "aggregations": {
    "centroid": {
      "location": {
        "x": 396.6213124593099,
        "y": 5100.982991536458
      },
      "count": 6
    }
  }
}
```

The `cartesian_centroid` aggregation is more interesting when combined as a sub-aggregation to other bucket aggregations.

Example:

```console
POST /museums/_search?size=0
{
  "aggs": {
    "cities": {
      "terms": { "field": "city.keyword" },
      "aggs": {
        "centroid": {
          "cartesian_centroid": { "field": "location" }
        }
      }
    }
  }
}
```

The above example uses `cartesian_centroid` as a sub-aggregation to a [terms](/reference/aggregations/search-aggregations-bucket-terms-aggregation.md) bucket aggregation for finding the central location for museums in each city.

The response for the above aggregation:

```console-result
{
  ...
  "aggregations": {
    "cities": {
      "sum_other_doc_count": 0,
      "doc_count_error_upper_bound": 0,
      "buckets": [
        {
          "key": "Amsterdam",
          "doc_count": 3,
          "centroid": {
            "location": {
              "x": 490.9563293457031,
              "y": 5237.16552734375
            },
            "count": 3
          }
        },
        {
          "key": "Paris",
          "doc_count": 2,
          "centroid": {
            "location": {
              "x": 233.16944885253906,
              "y": 4886.0556640625
            },
            "count": 2
          }
        },
        {
          "key": "Antwerp",
          "doc_count": 1,
          "centroid": {
            "location": {
              "x": 440.5199890136719,
              "y": 5122.2900390625
            },
            "count": 1
          }
        }
      ]
    }
  }
}
```


## Cartesian Centroid Aggregation on `shape` fields [cartesian-centroid-aggregation-geo-shape]

The centroid metric for shapes is more nuanced than for points. The centroid of a specific aggregation bucket containing shapes is the centroid of the highest-dimensionality shape type in the bucket. For example, if a bucket contains shapes consisting of polygons and lines, then the lines do not contribute to the centroid metric. Each type of shape’s centroid is calculated differently. Envelopes and circles ingested via the [Circle](/reference/enrich-processor/ingest-circle-processor.md) are treated as polygons.

| Geometry Type | Centroid Calculation |
| --- | --- |
| [Multi]Point | equally weighted average of all the coordinates |
| [Multi]LineString | a weighted average of all the centroids of each segment, where the weight of each segment is its length in the same units as the coordinates |
| [Multi]Polygon | a weighted average of all the centroids of all the triangles of a polygon where the triangles are formed by every two consecutive vertices and the starting-point.holes have negative weights. weights represent the area of the triangle is calculated in the square of the units of the coordinates |
| GeometryCollection | The centroid of all the underlying geometries with the highest dimension. If Polygons and Lines and/or Points, then lines and/or points are ignored.If Lines and Points, then points are ignored |

Example:

```console
PUT /places
{
  "mappings": {
    "properties": {
      "geometry": {
        "type": "shape"
      }
    }
  }
}

POST /places/_bulk?refresh
{"index":{"_id":1}}
{"name": "NEMO Science Museum", "geometry": "POINT(491.2350 5237.4081)" }
{"index":{"_id":2}}
{"name": "Sportpark De Weeren", "geometry": { "type": "Polygon", "coordinates": [ [ [ 496.5305328369141, 5239.347642069457 ], [ 496.6979026794433, 5239.1721758934835 ], [ 496.9425201416015, 5239.238958618537 ], [ 496.7944622039794, 5239.420969150824 ], [ 496.5305328369141, 5239.347642069457 ] ] ] } }

POST /places/_search?size=0
{
  "aggs": {
    "centroid": {
      "cartesian_centroid": {
        "field": "geometry"
      }
    }
  }
}
```

```console-result
{
  ...
  "aggregations": {
    "centroid": {
      "location": {
        "x": 496.74041748046875,
        "y": 5239.29638671875
      },
      "count": 2
    }
  }
}
```

