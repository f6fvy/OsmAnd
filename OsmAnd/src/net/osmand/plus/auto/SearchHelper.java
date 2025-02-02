package net.osmand.plus.auto;

import static android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE;
import static net.osmand.search.core.SearchCoreFactory.MAX_DEFAULT_SEARCH_RADIUS;
import static net.osmand.search.core.SearchCoreFactory.SEARCH_AMENITY_TYPE_PRIORITY;

import android.graphics.drawable.Drawable;
import android.text.SpannableString;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarLocation;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Metadata;
import androidx.car.app.model.Place;
import androidx.car.app.model.Row;
import androidx.core.graphics.drawable.IconCompat;

import net.osmand.plus.utils.AndroidUtils;
import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.PoiType;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.helpers.SearchHistoryHelper;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.plus.search.listitems.QuickSearchListItem;
import net.osmand.search.SearchUICore;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchPhrase;
import net.osmand.search.core.SearchResult;
import net.osmand.search.core.SearchSettings;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

public class SearchHelper {

	public static final double DISTANCE_THRESHOLD = 70000; // 70km

	private final OsmandApplication app;
	private final SearchUICore searchUICore;
	private final boolean showDescription;
	private final int contentLimit;

	@Nullable
	private String searchQuery;
	private List<SearchResult> searchResults;

	private int searchRadiusLevel = 1;
	private boolean searching;
	private boolean useMapCenter;
	private LatLon searchLocation;
	private String searchHint;

	private SearchHelperListener listener;

	public interface SearchHelperListener {
		void onClickSearchResult(@NonNull SearchResult sr);

		void onClickSearchMore();

		void onSearchDone(@NonNull SearchPhrase phrase, @Nullable List<SearchResult> searchResults,
						  @Nullable ItemList itemList, int resultsCount);
	}

	SearchHelper(@NonNull OsmandApplication app, boolean showDescription, int contentLimit) {
		this.app = app;
		this.searchUICore = app.getSearchUICore().getCore();
		this.showDescription = showDescription;
		this.contentLimit = contentLimit;
		setupSearchSettings(true);
	}

	public int getContentLimit() {
		return contentLimit;
	}

	public void resetSearchRadius() {
		searchRadiusLevel = 1;
	}

	public boolean isSearching() {
		return searching;
	}

	@Nullable
	public String getSearchQuery() {
		return searchQuery;
	}

	public LatLon getSearchLocation() {
		return searchLocation;
	}

	@Nullable
	public String getSearchHint() {
		return searchHint;
	}

	@Nullable
	public List<SearchResult> getSearchResults() {
		return searchResults;
	}

	public SearchHelperListener getListener() {
		return listener;
	}

	public void setListener(SearchHelperListener listener) {
		this.listener = listener;
	}

	public SearchSettings setupSearchSettings(boolean resetPhrase) {
		Location location = app.getLocationProvider().getLastKnownLocation();
		SearchUICore core = app.getSearchUICore().getCore();
		if (resetPhrase) {
			core.resetPhrase();
		}
		int radiusLevel = this.searchRadiusLevel;
		if (radiusLevel < 1) {
			radiusLevel = 1;
		} else if (radiusLevel > MAX_DEFAULT_SEARCH_RADIUS) {
			radiusLevel = MAX_DEFAULT_SEARCH_RADIUS;
		}
		LatLon searchLatLon;
		LatLon clt = app.getOsmandMap().getMapView().getCurrentRotatedTileBox().getCenterLatLon();
		searchLatLon = clt;
		if (location != null) {
			double d = MapUtils.getDistance(clt, location.getLatitude(), location.getLongitude());
			if (d < DISTANCE_THRESHOLD) {
				searchLatLon = new LatLon(location.getLatitude(), location.getLongitude());
				useMapCenter = false;
			} else {
				useMapCenter = true;
			}
		} else {
			useMapCenter = true;
		}
		searchLocation = searchLatLon;
		String locale = app.getSettings().MAP_PREFERRED_LOCALE.get();
		boolean transliterate = app.getSettings().MAP_TRANSLITERATE_NAMES.get();
		SearchSettings searchSettings = new SearchSettings(core.getSearchSettings())
				.resetSearchTypes()
				.setRadiusLevel(radiusLevel)
				.setEmptyQueryAllowed(false)
				.setSortByName(false)
				.setLang(locale, transliterate)
				.setOriginalLocation(searchLatLon);

		updateSearchHint(searchSettings);
		return searchSettings;
	}

	private void updateSearchHint(@NonNull SearchSettings searchSettings) {
		Location location = app.getLocationProvider().getLastKnownLocation();
		LatLon searchLocation = searchSettings.getOriginalLocation();
		if (useMapCenter && location != null && searchLocation != null) {
			double d = MapUtils.getDistance(searchLocation, location.getLatitude(), location.getLongitude());
			String dist = OsmAndFormatter.getFormattedDistance((float) d, app);
			searchHint = app.getString(R.string.dist_away_from_my_location, dist);
		} else {
			searchHint = app.getString(R.string.search_poi_category_hint);
		}
	}

	public void runSearch(@NonNull String query) {
		searching = true;
		searchQuery = query;
		SearchSettings searchSettings = setupSearchSettings(false);
		searchUICore.setOnResultsComplete(() -> {
			ItemList.Builder itemList = new ItemList.Builder();
			SearchUICore.SearchResultCollection resultCollection = searchUICore.getCurrentSearchResult();
			int count = 0;
			List<SearchResult> searchResults = new ArrayList<>();
			for (SearchResult r : resultCollection.getCurrentSearchResults()) {
				String name = QuickSearchListItem.getName(app, r);
				if (Algorithms.isEmpty(name)) {
					continue;
				}
				Drawable icon = QuickSearchListItem.getIcon(app, r);
				String typeName = showDescription ? QuickSearchListItem.getTypeName(app, r) : "";
				itemList.setNoItemsMessage(app.getString(R.string.search_nothing_found));
				Row.Builder builder = buildSearchRow(searchSettings.getOriginalLocation(), r.location, name, icon, typeName);
				if (builder != null) {
					builder.setOnClickListener(() -> {
						if (listener != null) {
							listener.onClickSearchResult(r);
						}
					});
					itemList.addItem(builder.build());
					searchResults.add(r);
					count++;
					if (count >= contentLimit) {
						break;
					}
				}
			}
			SearchPhrase phrase = searchUICore.getPhrase();
			if (searchUICore.isSearchMoreAvailable(phrase)) {
				Row.Builder builder = new Row.Builder();
				builder.setTitle(app.getString(R.string.increase_search_radius));
				int minimalSearchRadius = searchUICore.getMinimalSearchRadius(phrase);
				if (count == 0 && minimalSearchRadius != Integer.MAX_VALUE) {
					double rd = OsmAndFormatter.calculateRoundedDist(minimalSearchRadius, app);
					builder.addText(app.getString(R.string.nothing_found_in_radius) + " "
							+ OsmAndFormatter.getFormattedDistance((float) rd, app, false));
				}
				builder.setOnClickListener(this::onClickSearchMore);
				builder.setBrowsable(true);
				itemList.addItem(builder.build());
			}
			int resultsCount = count;
			app.runInUIThread(() -> {
				this.searchResults = searchResults;
				searching = false;
				if (listener != null) {
					listener.onSearchDone(phrase, searchResults, itemList.build(), resultsCount);
				}
			});
		});
		searchUICore.search(searchQuery, true, null, searchSettings);
	}

	public void completeQueryWithObject(@NonNull SearchResult sr) {
		if (sr.object instanceof AbstractPoiType) {
			SearchHistoryHelper.getInstance(app).addNewItemToHistory((AbstractPoiType) sr.object);
		} else if (sr.object instanceof PoiUIFilter) {
			SearchHistoryHelper.getInstance(app).addNewItemToHistory((PoiUIFilter) sr.object);
		}
		if (sr.object instanceof PoiType && ((PoiType) sr.object).isAdditional()) {
			PoiType additional = (PoiType) sr.object;
			AbstractPoiType parent = additional.getParentType();
			if (parent != null) {
				PoiUIFilter custom = app.getPoiFilters().getFilterById(PoiUIFilter.STD_PREFIX + parent.getKeyName());
				if (custom != null) {
					custom.clearFilter();
					custom.updateTypesToAccept(parent);
					custom.setFilterByName(additional.getKeyName().replace('_', ':').toLowerCase());

					SearchPhrase phrase = searchUICore.getPhrase();
					sr = new SearchResult(phrase);
					sr.localeName = custom.getName();
					sr.object = custom;
					sr.priority = SEARCH_AMENITY_TYPE_PRIORITY;
					sr.priorityDistance = 0;
					sr.objectType = ObjectType.POI_TYPE;
				}
			}
		}
		searchUICore.selectSearchResult(sr);
		String searchQuery = searchUICore.getPhrase().getText(true);
		if (searchRadiusLevel != 1) {
			searchRadiusLevel = 1;
		}
		runSearch(searchQuery);
	}

	@Nullable
	public Row.Builder buildSearchRow(@Nullable LatLon searchLocation, @Nullable LatLon placeLocation,
									  @NonNull String name, @Nullable Drawable icon, @Nullable String typeName) {
		Row.Builder builder = new Row.Builder();
		if (icon != null) {
			builder.setImage(new CarIcon.Builder(IconCompat.createWithBitmap(AndroidUtils.drawableToBitmap(icon))).build());
		}
		builder.setTitle(name);
		if (name.equals(typeName)) {
			typeName = "";
		}
		if (placeLocation != null && searchLocation != null) {
			float dist = (float) MapUtils.getDistance(placeLocation, searchLocation);
			SpannableString description = !Algorithms.isEmpty(typeName)
					? new SpannableString("  • " + typeName) : new SpannableString(" ");
			DistanceSpan distanceSpan = DistanceSpan.create(TripHelper.getDistance(app, dist));
			description.setSpan(distanceSpan, 0, 1, SPAN_INCLUSIVE_INCLUSIVE);
			builder.addText(description);
			builder.setMetadata(new Metadata.Builder().setPlace(new Place.Builder(
					CarLocation.create(placeLocation.getLatitude(), placeLocation.getLongitude())).build()).build());
		} else {
			if (!Algorithms.isEmpty(typeName)) {
				builder.addText(typeName);
			}
			builder.setBrowsable(true);
		}
		return builder;
	}

	private void onClickSearchMore() {
		searchRadiusLevel++;
		if (!Algorithms.isEmpty(searchQuery)) {
			runSearch(searchQuery);
		}
		if (listener != null) {
			listener.onClickSearchMore();
		}
	}
}
