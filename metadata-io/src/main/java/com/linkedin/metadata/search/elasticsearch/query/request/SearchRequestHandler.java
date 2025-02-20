package com.linkedin.metadata.search.elasticsearch.query.request;

import static com.linkedin.metadata.search.utils.ESUtils.NAME_SUGGESTION;
import static com.linkedin.metadata.search.utils.SearchUtils.applyDefaultSearchFlags;
import static com.linkedin.metadata.utils.SearchUtil.*;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.DoubleMap;
import com.linkedin.metadata.config.search.SearchConfiguration;
import com.linkedin.metadata.config.search.custom.CustomSearchConfiguration;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.SearchableFieldSpec;
import com.linkedin.metadata.models.annotation.SearchableAnnotation;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.search.AggregationMetadata;
import com.linkedin.metadata.search.AggregationMetadataArray;
import com.linkedin.metadata.search.MatchedField;
import com.linkedin.metadata.search.MatchedFieldArray;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchEntityArray;
import com.linkedin.metadata.search.SearchResult;
import com.linkedin.metadata.search.SearchResultMetadata;
import com.linkedin.metadata.search.SearchSuggestion;
import com.linkedin.metadata.search.SearchSuggestionArray;
import com.linkedin.metadata.search.features.Features;
import com.linkedin.metadata.search.utils.ESUtils;
import com.linkedin.util.Pair;
import io.opentelemetry.extension.annotations.WithSpan;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.text.Text;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.opensearch.search.fetch.subphase.highlight.HighlightField;
import org.opensearch.search.suggest.term.TermSuggestion;

@Slf4j
public class SearchRequestHandler {
  private static final SearchFlags DEFAULT_SERVICE_SEARCH_FLAGS =
      new SearchFlags()
          .setFulltext(false)
          .setMaxAggValues(20)
          .setSkipCache(false)
          .setSkipAggregates(false)
          .setSkipHighlighting(false);
  private static final Map<List<EntitySpec>, SearchRequestHandler> REQUEST_HANDLER_BY_ENTITY_NAME =
      new ConcurrentHashMap<>();
  private final List<EntitySpec> _entitySpecs;
  private final Set<String> _defaultQueryFieldNames;
  private final HighlightBuilder _highlights;

  private final SearchConfiguration _configs;
  private final SearchQueryBuilder _searchQueryBuilder;
  private final AggregationQueryBuilder _aggregationQueryBuilder;
  private final Map<String, Set<SearchableAnnotation.FieldType>> searchableFieldTypes;

  private SearchRequestHandler(
      @Nonnull EntitySpec entitySpec,
      @Nonnull SearchConfiguration configs,
      @Nullable CustomSearchConfiguration customSearchConfiguration) {
    this(ImmutableList.of(entitySpec), configs, customSearchConfiguration);
  }

  private SearchRequestHandler(
      @Nonnull List<EntitySpec> entitySpecs,
      @Nonnull SearchConfiguration configs,
      @Nullable CustomSearchConfiguration customSearchConfiguration) {
    _entitySpecs = entitySpecs;
    Map<EntitySpec, List<SearchableAnnotation>> entitySearchAnnotations =
        getSearchableAnnotations();
    List<SearchableAnnotation> annotations =
        entitySearchAnnotations.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    _defaultQueryFieldNames = getDefaultQueryFieldNames(annotations);
    _highlights = getHighlights();
    _searchQueryBuilder = new SearchQueryBuilder(configs, customSearchConfiguration);
    _aggregationQueryBuilder = new AggregationQueryBuilder(configs, entitySearchAnnotations);
    _configs = configs;
    searchableFieldTypes =
        _entitySpecs.stream()
            .flatMap(entitySpec -> entitySpec.getSearchableFieldTypes().entrySet().stream())
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (set1, set2) -> {
                      set1.addAll(set2);
                      return set1;
                    }));
  }

  public static SearchRequestHandler getBuilder(
      @Nonnull EntitySpec entitySpec,
      @Nonnull SearchConfiguration configs,
      @Nullable CustomSearchConfiguration customSearchConfiguration) {
    return REQUEST_HANDLER_BY_ENTITY_NAME.computeIfAbsent(
        ImmutableList.of(entitySpec),
        k -> new SearchRequestHandler(entitySpec, configs, customSearchConfiguration));
  }

  public static SearchRequestHandler getBuilder(
      @Nonnull List<EntitySpec> entitySpecs,
      @Nonnull SearchConfiguration configs,
      @Nullable CustomSearchConfiguration customSearchConfiguration) {
    return REQUEST_HANDLER_BY_ENTITY_NAME.computeIfAbsent(
        ImmutableList.copyOf(entitySpecs),
        k -> new SearchRequestHandler(entitySpecs, configs, customSearchConfiguration));
  }

  private Map<EntitySpec, List<SearchableAnnotation>> getSearchableAnnotations() {
    return _entitySpecs.stream()
        .map(
            spec ->
                Pair.of(
                    spec,
                    spec.getSearchableFieldSpecs().stream()
                        .map(SearchableFieldSpec::getSearchableAnnotation)
                        .collect(Collectors.toList())))
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  @VisibleForTesting
  private Set<String> getDefaultQueryFieldNames(List<SearchableAnnotation> annotations) {
    return Stream.concat(
            annotations.stream()
                .filter(SearchableAnnotation::isQueryByDefault)
                .map(SearchableAnnotation::getFieldName),
            Stream.of("urn"))
        .collect(Collectors.toSet());
  }

  public BoolQueryBuilder getFilterQuery(@Nullable Filter filter) {
    return getFilterQuery(filter, searchableFieldTypes);
  }

  public static BoolQueryBuilder getFilterQuery(
      @Nullable Filter filter,
      Map<String, Set<SearchableAnnotation.FieldType>> searchableFieldTypes) {
    BoolQueryBuilder filterQuery = ESUtils.buildFilterQuery(filter, false, searchableFieldTypes);

    return filterSoftDeletedByDefault(filter, filterQuery);
  }

  /**
   * Constructs the search query based on the query request.
   *
   * <p>TODO: This part will be replaced by searchTemplateAPI when the elastic is upgraded to 6.4 or
   * later
   *
   * @param input the search input text
   * @param filter the search filter
   * @param from index to start the search from
   * @param size the number of search hits to return
   * @param searchFlags Various flags controlling search query options
   * @param facets list of facets we want aggregations for
   * @return a valid search request
   */
  @Nonnull
  @WithSpan
  public SearchRequest getSearchRequest(
      @Nonnull String input,
      @Nullable Filter filter,
      @Nullable SortCriterion sortCriterion,
      int from,
      int size,
      @Nullable SearchFlags searchFlags,
      @Nullable List<String> facets) {
    SearchFlags finalSearchFlags =
        applyDefaultSearchFlags(searchFlags, input, DEFAULT_SERVICE_SEARCH_FLAGS);

    SearchRequest searchRequest = new SearchRequest();
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();

    searchSourceBuilder.from(from);
    searchSourceBuilder.size(size);
    searchSourceBuilder.fetchSource("urn", null);

    BoolQueryBuilder filterQuery = getFilterQuery(filter);
    searchSourceBuilder.query(
        QueryBuilders.boolQuery()
            .must(getQuery(input, Boolean.TRUE.equals(finalSearchFlags.isFulltext())))
            .filter(filterQuery));
    if (Boolean.FALSE.equals(finalSearchFlags.isSkipAggregates())) {
      _aggregationQueryBuilder.getAggregations(facets).forEach(searchSourceBuilder::aggregation);
    }
    if (Boolean.FALSE.equals(finalSearchFlags.isSkipHighlighting())) {
      searchSourceBuilder.highlighter(_highlights);
    }
    ESUtils.buildSortOrder(searchSourceBuilder, sortCriterion, _entitySpecs);

    if (Boolean.TRUE.equals(finalSearchFlags.isGetSuggestions())) {
      ESUtils.buildNameSuggestions(searchSourceBuilder, input);
    }

    searchRequest.source(searchSourceBuilder);
    log.debug("Search request is: " + searchRequest);

    return searchRequest;
  }

  /**
   * Constructs the search query based on the query request.
   *
   * <p>TODO: This part will be replaced by searchTemplateAPI when the elastic is upgraded to 6.4 or
   * later
   *
   * @param input the search input text
   * @param filter the search filter
   * @param sort sort values of the last result of the previous page
   * @param size the number of search hits to return
   * @return a valid search request
   */
  @Nonnull
  @WithSpan
  public SearchRequest getSearchRequest(
      @Nonnull String input,
      @Nullable Filter filter,
      @Nullable SortCriterion sortCriterion,
      @Nullable Object[] sort,
      @Nullable String pitId,
      @Nullable String keepAlive,
      int size,
      SearchFlags searchFlags,
      @Nullable List<String> facets) {
    SearchRequest searchRequest = new PITAwareSearchRequest();
    SearchFlags finalSearchFlags =
        applyDefaultSearchFlags(searchFlags, input, DEFAULT_SERVICE_SEARCH_FLAGS);
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();

    ESUtils.setSearchAfter(searchSourceBuilder, sort, pitId, keepAlive);

    searchSourceBuilder.size(size);
    searchSourceBuilder.fetchSource("urn", null);

    BoolQueryBuilder filterQuery = getFilterQuery(filter);
    searchSourceBuilder.query(
        QueryBuilders.boolQuery()
            .must(getQuery(input, Boolean.TRUE.equals(finalSearchFlags.isFulltext())))
            .filter(filterQuery));
    if (Boolean.FALSE.equals(finalSearchFlags.isSkipAggregates())) {
      _aggregationQueryBuilder.getAggregations(facets).forEach(searchSourceBuilder::aggregation);
    }
    if (Boolean.FALSE.equals(finalSearchFlags.isSkipHighlighting())) {
      searchSourceBuilder.highlighter(_highlights);
    }
    ESUtils.buildSortOrder(searchSourceBuilder, sortCriterion, _entitySpecs);
    searchRequest.source(searchSourceBuilder);
    log.debug("Search request is: " + searchRequest);
    searchRequest.indicesOptions(null);

    return searchRequest;
  }

  /**
   * Returns a {@link SearchRequest} given filters to be applied to search query and sort criterion
   * to be applied to search results.
   *
   * @param filters {@link Filter} list of conditions with fields and values
   * @param sortCriterion {@link SortCriterion} to be applied to the search results
   * @param from index to start the search from
   * @param size the number of search hits to return
   * @return {@link SearchRequest} that contains the filtered query
   */
  @Nonnull
  public SearchRequest getFilterRequest(
      @Nullable Filter filters, @Nullable SortCriterion sortCriterion, int from, int size) {
    SearchRequest searchRequest = new SearchRequest();

    BoolQueryBuilder filterQuery = getFilterQuery(filters);
    final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder.query(filterQuery);
    searchSourceBuilder.from(from).size(size);
    ESUtils.buildSortOrder(searchSourceBuilder, sortCriterion, _entitySpecs);
    searchRequest.source(searchSourceBuilder);

    return searchRequest;
  }

  /**
   * Get search request to aggregate and get document counts per field value
   *
   * @param field Field to aggregate by
   * @param filter {@link Filter} list of conditions with fields and values
   * @param limit number of aggregations to return
   * @return {@link SearchRequest} that contains the aggregation query
   */
  @Nonnull
  public SearchRequest getAggregationRequest(
      @Nonnull String field, @Nullable Filter filter, int limit) {
    SearchRequest searchRequest = new SearchRequest();
    BoolQueryBuilder filterQuery = getFilterQuery(filter);

    final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder.query(filterQuery);
    searchSourceBuilder.size(0);
    searchSourceBuilder.aggregation(
        AggregationBuilders.terms(field).field(ESUtils.toKeywordField(field, false)).size(limit));
    searchRequest.source(searchSourceBuilder);

    return searchRequest;
  }

  public QueryBuilder getQuery(@Nonnull String query, boolean fulltext) {
    return _searchQueryBuilder.buildQuery(_entitySpecs, query, fulltext);
  }

  @VisibleForTesting
  public HighlightBuilder getHighlights() {
    HighlightBuilder highlightBuilder = new HighlightBuilder();

    // Don't set tags to get the original field value
    highlightBuilder.preTags("");
    highlightBuilder.postTags("");

    // Check for each field name and any subfields
    _defaultQueryFieldNames.stream()
        .flatMap(fieldName -> Stream.of(fieldName, fieldName + ".*"))
        .distinct()
        .forEach(highlightBuilder::field);

    return highlightBuilder;
  }

  @WithSpan
  public SearchResult extractResult(
      @Nonnull SearchResponse searchResponse, Filter filter, int from, int size) {
    int totalCount = (int) searchResponse.getHits().getTotalHits().value;
    List<SearchEntity> resultList = getResults(searchResponse);
    SearchResultMetadata searchResultMetadata = extractSearchResultMetadata(searchResponse, filter);

    return new SearchResult()
        .setEntities(new SearchEntityArray(resultList))
        .setMetadata(searchResultMetadata)
        .setFrom(from)
        .setPageSize(size)
        .setNumEntities(totalCount);
  }

  @WithSpan
  public ScrollResult extractScrollResult(
      @Nonnull SearchResponse searchResponse,
      Filter filter,
      @Nullable String scrollId,
      @Nullable String keepAlive,
      int size,
      boolean supportsPointInTime) {
    int totalCount = (int) searchResponse.getHits().getTotalHits().value;
    List<SearchEntity> resultList = getResults(searchResponse);
    SearchResultMetadata searchResultMetadata = extractSearchResultMetadata(searchResponse, filter);
    SearchHit[] searchHits = searchResponse.getHits().getHits();
    // Only return next scroll ID if there are more results, indicated by full size results
    String nextScrollId = null;
    if (searchHits.length == size) {
      Object[] sort = searchHits[searchHits.length - 1].getSortValues();
      long expirationTimeMs = 0L;
      if (keepAlive != null && supportsPointInTime) {
        expirationTimeMs =
            TimeValue.parseTimeValue(keepAlive, "expirationTime").getMillis()
                + System.currentTimeMillis();
      }
      nextScrollId =
          new SearchAfterWrapper(sort, searchResponse.pointInTimeId(), expirationTimeMs)
              .toScrollId();
    }

    ScrollResult scrollResult =
        new ScrollResult()
            .setEntities(new SearchEntityArray(resultList))
            .setMetadata(searchResultMetadata)
            .setPageSize(size)
            .setNumEntities(totalCount);

    if (nextScrollId != null) {
      scrollResult.setScrollId(nextScrollId);
    }
    return scrollResult;
  }

  @Nonnull
  private List<MatchedField> extractMatchedFields(@Nonnull SearchHit hit) {
    Map<String, HighlightField> highlightedFields = hit.getHighlightFields();
    // Keep track of unique field values that matched for a given field name
    Map<String, Set<String>> highlightedFieldNamesAndValues = new HashMap<>();
    for (Map.Entry<String, HighlightField> entry : highlightedFields.entrySet()) {
      // Get the field name from source e.g. name.delimited -> name
      Optional<String> fieldName = getFieldName(entry.getKey());
      if (fieldName.isEmpty()) {
        continue;
      }
      if (!highlightedFieldNamesAndValues.containsKey(fieldName.get())) {
        highlightedFieldNamesAndValues.put(fieldName.get(), new HashSet<>());
      }
      for (Text fieldValue : entry.getValue().getFragments()) {
        highlightedFieldNamesAndValues.get(fieldName.get()).add(fieldValue.string());
      }
    }
    // fallback matched query, non-analyzed field
    for (String queryName : hit.getMatchedQueries()) {
      if (!highlightedFieldNamesAndValues.containsKey(queryName)) {
        if (hit.getFields().containsKey(queryName)) {
          for (Object fieldValue : hit.getFields().get(queryName).getValues()) {
            highlightedFieldNamesAndValues
                .computeIfAbsent(queryName, k -> new HashSet<>())
                .add(fieldValue.toString());
          }
        } else {
          highlightedFieldNamesAndValues.put(queryName, Set.of(""));
        }
      }
    }
    return highlightedFieldNamesAndValues.entrySet().stream()
        .flatMap(
            entry ->
                entry.getValue().stream()
                    .map(value -> new MatchedField().setName(entry.getKey()).setValue(value)))
        .collect(Collectors.toList());
  }

  @Nonnull
  private Optional<String> getFieldName(String matchedField) {
    return _defaultQueryFieldNames.stream().filter(matchedField::startsWith).findFirst();
  }

  private Map<String, Double> extractFeatures(@Nonnull SearchHit searchHit) {
    return ImmutableMap.of(
        Features.Name.SEARCH_BACKEND_SCORE.toString(), (double) searchHit.getScore());
  }

  private SearchEntity getResult(@Nonnull SearchHit hit) {
    return new SearchEntity()
        .setEntity(getUrnFromSearchHit(hit))
        .setMatchedFields(new MatchedFieldArray(extractMatchedFields(hit)))
        .setScore(hit.getScore())
        .setFeatures(new DoubleMap(extractFeatures(hit)));
  }

  /**
   * Gets list of entities returned in the search response
   *
   * @param searchResponse the raw search response from search engine
   * @return List of search entities
   */
  @Nonnull
  private List<SearchEntity> getResults(@Nonnull SearchResponse searchResponse) {
    return Arrays.stream(searchResponse.getHits().getHits())
        .map(this::getResult)
        .collect(Collectors.toList());
  }

  @Nonnull
  private Urn getUrnFromSearchHit(@Nonnull SearchHit hit) {
    try {
      return Urn.createFromString(hit.getSourceAsMap().get("urn").toString());
    } catch (URISyntaxException e) {
      throw new RuntimeException("Invalid urn in search document " + e);
    }
  }

  /**
   * Extracts SearchResultMetadata section.
   *
   * @param searchResponse the raw {@link SearchResponse} as obtained from the search engine
   * @param filter the provided Filter to use with Elasticsearch
   * @return {@link SearchResultMetadata} with aggregation and list of urns obtained from {@link
   *     SearchResponse}
   */
  @Nonnull
  private SearchResultMetadata extractSearchResultMetadata(
      @Nonnull SearchResponse searchResponse, @Nullable Filter filter) {
    final SearchResultMetadata searchResultMetadata =
        new SearchResultMetadata().setAggregations(new AggregationMetadataArray());

    final List<AggregationMetadata> aggregationMetadataList =
        _aggregationQueryBuilder.extractAggregationMetadata(searchResponse, filter);
    searchResultMetadata.setAggregations(new AggregationMetadataArray(aggregationMetadataList));

    final List<SearchSuggestion> searchSuggestions = extractSearchSuggestions(searchResponse);
    searchResultMetadata.setSuggestions(new SearchSuggestionArray(searchSuggestions));

    return searchResultMetadata;
  }

  private List<SearchSuggestion> extractSearchSuggestions(@Nonnull SearchResponse searchResponse) {
    final List<SearchSuggestion> searchSuggestions = new ArrayList<>();
    if (searchResponse.getSuggest() != null) {
      TermSuggestion termSuggestion = searchResponse.getSuggest().getSuggestion(NAME_SUGGESTION);
      if (termSuggestion != null && !termSuggestion.getEntries().isEmpty()) {
        termSuggestion
            .getEntries()
            .get(0)
            .getOptions()
            .forEach(
                suggestOption -> {
                  SearchSuggestion searchSuggestion = new SearchSuggestion();
                  searchSuggestion.setText(String.valueOf(suggestOption.getText()));
                  searchSuggestion.setFrequency(suggestOption.getFreq());
                  searchSuggestion.setScore(suggestOption.getScore());
                  searchSuggestions.add(searchSuggestion);
                });
      }
    }
    return searchSuggestions;
  }
}
