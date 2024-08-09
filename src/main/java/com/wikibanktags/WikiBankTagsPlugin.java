package com.wikibanktags;

import com.google.common.base.MoreObjects;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.banktags.BankTagsPlugin.*;

@Slf4j
@PluginDescriptor(
		name = "Wiki Bank Tags",
		description = "Creates bank tags from wiki categories",
		tags = {"bank", "tags", "wiki"}
)
public class WikiBankTagsPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private WikiBankTagsConfig config;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ItemManager itemManager;
	@Inject
	private TagManager tagManager;

	private static final String ITEMS_QUERY_TEMPLATE = "https://oldschool.runescape.wiki/api.php?action=query&list=categorymembers&cmtitle=Category:items&cmnamespace=0&format=json&cmlimit=max&formatversion=2";
	private static final String CATEGORY_QUERY_TEMPLATE = "https://oldschool.runescape.wiki/api.php?action=query&list=categorymembers&cmtitle=Category:%s&cmlimit=max&format=json&formatversion=2";
	private static final HttpClient CLIENT = HttpClient.newHttpClient();
	private static Set<String> cachedItems = null;

	@Override
	protected void startUp() throws Exception {
		log.info("Wiki Bank Tags started!");
		initializeItemCache();
	}

	@Override
	protected void shutDown() throws Exception {
		log.info("Wiki Bank Tags stopped!");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Wiki Bank Tags command is " + config.command(), null);
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted)
	{
		if (commandExecuted.getCommand().equals(config.command())) {
			String[] args = commandExecuted.getArguments();
			if (args.length != 1) {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Usage: " + config.command() + " category", null);
				return;
			}

			String category = String.join(" ", args);
			if (!tagManager.getItemsForTag(category).isEmpty()) {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", category + " already exists and contains " + tagManager.getItemsForTag(category).size() + " items.", null);
				return;
			}
			try {
				List<WikiItem> items = fetchCategoryItems(category);
				if (items.isEmpty()) {
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "No items found for category: " + category, null);
					return;
				}
				createBankTag(category, items);
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Created bank tag '" + category + "' with " + items.size() + " items.", null);
			} catch (IOException | InterruptedException e) {
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Failed to fetch data: " + e.getMessage(), null);
				log.error("Failed to fetch data", e);
			}
		}
	}

	private void initializeItemCache() throws IOException, InterruptedException {
		if (cachedItems == null) {
			cachedItems = fetchAllItems();
		}
	}

	private Set<String> fetchAllItems() throws IOException, InterruptedException {
		Set<String> items = new HashSet<>();
		String continueToken = null;
		JsonParser parser = new JsonParser();

		do {
			String url = ITEMS_QUERY_TEMPLATE;
			if (continueToken != null) {
				url += "&cmcontinue=" + URLEncoder.encode(continueToken, StandardCharsets.UTF_8);
			}

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.build();

			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			String data = response.body();

			JsonObject jsonObject = parser.parse(data).getAsJsonObject();
			JsonObject query = jsonObject.getAsJsonObject("query");
			if (query != null) {
				JsonArray categoryMembers = query.getAsJsonArray("categorymembers");
				for (JsonElement memberElement : categoryMembers) {
					JsonObject member = memberElement.getAsJsonObject();
					String title = member.get("title").getAsString();
					items.add(title);
				}
			}

			// Check for continuation
			JsonObject continueObject = jsonObject.getAsJsonObject("continue");
			continueToken = continueObject != null ? continueObject.get("cmcontinue").getAsString() : null;

		} while (continueToken != null);

		log.info("Cached {} items from the Items category", items.size());
		return items;
	}

	private List<WikiItem> fetchCategoryItems(String category) throws IOException, InterruptedException {
		initializeItemCache(); // Ensure the cache is initialized

		Set<String> targetItems = fetchItemsInCategory(category);

		// Filter the target items against the cached items
		List<WikiItem> items = new ArrayList<>();
		for (String itemName : targetItems) {
			if (cachedItems.contains(itemName)) {
				int itemId = getItemIdByName(itemName);
				if (itemId != -1) {
					items.add(new WikiItem(itemName, itemId));
				}
			}
		}

		// Log filtered results
		log.info("Filtered {} out of {} items from category '{}'", items.size(), targetItems.size(), category);
		return items;
	}


	private Set<String> fetchItemsInCategory(String category) throws IOException, InterruptedException {
		Set<String> items = new HashSet<>();
		String continueToken = null;
		JsonParser parser = new JsonParser();

		do {
			String url = String.format(CATEGORY_QUERY_TEMPLATE, URLEncoder.encode(category, StandardCharsets.UTF_8));
			if (continueToken != null) {
				url += "&cmcontinue=" + URLEncoder.encode(continueToken, StandardCharsets.UTF_8);
			}

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.build();

			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			String data = response.body();

			JsonObject jsonObject = parser.parse(data).getAsJsonObject();
			JsonObject query = jsonObject.getAsJsonObject("query");
			if (query != null) {
				JsonArray categoryMembers = query.getAsJsonArray("categorymembers");
				for (JsonElement memberElement : categoryMembers) {
					JsonObject member = memberElement.getAsJsonObject();
					String title = member.get("title").getAsString();
					items.add(title);
				}
			}

			// Check for continuation
			JsonObject continueObject = jsonObject.getAsJsonObject("continue");
			continueToken = continueObject != null ? continueObject.get("cmcontinue").getAsString() : null;

		} while (continueToken != null);

		return items;
	}

	private int getItemIdByName(String name) {
		ItemPrice itemComposition = itemManager.search(name).stream()
				.findFirst()
				.orElse(null);
		return itemComposition != null ? itemComposition.getId() : -1;

	}

	private void createBankTag(String tagName, List<WikiItem> items) {
		String itemIds = items.stream()
				.map(item -> String.valueOf(item.getId()))
				.collect(Collectors.joining(","));

		log.info("Creating bank tag '{}' with item IDs: {}", tagName, itemIds);

		// Add the tag to the list of tabs
		String currentConfig = configManager.getConfiguration(CONFIG_GROUP, TAG_TABS_CONFIG);
		List<String> tabs = new ArrayList<>(Text.fromCSV(MoreObjects.firstNonNull(currentConfig, "")));
		if (!tabs.contains(tagName)) {
			tabs.add(tagName);
			String tags = Text.toCSV(tabs);
			configManager.setConfiguration(CONFIG_GROUP, TAG_TABS_CONFIG, tags);
			configManager.setConfiguration(CONFIG_GROUP, TAG_ICON_PREFIX + tagName, items.get(0).getId());
		}

		// Add the tag to the items
		for (WikiItem item : items) {
			tagManager.addTag(item.getId(), tagName, false);
		}

		log.info("Bank tag '{}' created with {} items", tagName, items.size());
	}

	@Provides
	com.wikibanktags.WikiBankTagsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(com.wikibanktags.WikiBankTagsConfig.class);
	}

	@Getter
	private static class WikiItem {
		private final String name;
		private final int id;

		public WikiItem(String name, int id) {
			this.name = name;
			this.id = id;
		}
	}
}
