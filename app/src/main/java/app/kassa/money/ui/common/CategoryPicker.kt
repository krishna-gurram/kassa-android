package app.kassa.money.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.entry.PanelSearch
import app.kassa.money.ui.entry.PanelTitle
import app.kassa.money.ui.entry.PickTile
import app.kassa.money.ui.entry.searchCategories
import app.kassa.money.ui.entry.subcategories
import app.kassa.money.ui.entry.topCategories
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/*
 * The one category picker. Add entry, the loan setup form, Edit details and the loan payment sheet all choose a category
 * with these pieces: the icon grid with search, then a category's subcategories with "No subcategory" and "+ New".
 * They hold no state of their own about what is being edited (the caller passes what is picked and gets the id back), so
 * the entry page keeps its [app.kassa.money.ui.entry.EntryState] and a bottom sheet keeps its form, and neither has a
 * second copy of the picker.
 */

/** Creates a subcategory under a category and hands back its id. AppRoot provides it; the picker's "+ New" calls it. */
val LocalAddSubcategory = staticCompositionLocalOf<(Category, String, (Long) -> Unit) -> Unit> { { _, _, _ -> } }

/** "Parent › Child" for a subcategory, the plain name for any other category. */
fun categoryPath(category: Category, categories: List<Category>): String {
    val parent = category.parentId?.let { id -> categories.firstOrNull { it.id == id } }
    return if (parent != null) "${parent.name} › ${category.name}" else category.name
}

/**
 * Categories of [kind] as an icon grid, A to Z; typing searches categories and subcategories together. A top-level
 * category that has subcategories calls [onOpenSubcategories] (so the caller shows [SubcategoryPickerPanel]); any other
 * choice calls [onPick] with its id. [onDone] is the Done button. Fills the height the caller gives it.
 */
@Composable
fun ColumnScope.CategoryPickerPanel(
    categories: List<Category>,
    kind: CategoryKind,
    selectedId: Long?,
    query: String,
    onQuery: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    wide: Boolean,
    onDone: () -> Unit,
    onPick: (Long) -> Unit,
    onOpenSubcategories: (Category) -> Unit,
) {
    val focus = LocalFocusManager.current
    val byId = remember(categories) { categories.associateBy { it.id } }
    val choices = remember(categories, kind) { topCategories(categories, kind) }
    val picked = selectedId?.let { byId[it] }
    val pickedTop = picked?.let { c -> c.parentId?.let { byId[it] } ?: c }
    // A top-level category with subcategories opens them; anything else is the answer.
    val choose: (Category) -> Unit = { c ->
        if (c.parentId == null && subcategories(categories, c).isNotEmpty()) onOpenSubcategories(c) else onPick(c.id)
    }

    PanelSearch(query, onQuery, stringResource(R.string.common_search_categories), onFocus = onSearching, onDone = onDone)
    val q = query.trim()
    if (q.isEmpty()) {
        LazyVerticalGrid(GridCells.Fixed(if (wide) 4 else 3), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(choices, key = { it.id }) { c ->
                PickTile(on = pickedTop?.id == c.id, minHeight = 66.dp, onClick = { choose(c) }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CategoryIconView(c.icon, Color(c.color), 34, fallback = c.name.take(1))
                        Text(c.name, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        return
    }
    val hits = searchCategories(categories, kind, q)
    if (hits.isEmpty()) Text(stringResource(R.string.common_no_category_matches, q), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(4.dp))
    LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(hits, key = { it.id }) { c ->
            val parent = c.parentId?.let { byId[it] }
            PickTile(on = picked?.id == c.id, onClick = { onSearching(false); focus.clearFocus(); choose(c) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconView((parent ?: c).icon, Color((parent ?: c).color), 26, fallback = c.name.take(1))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(c.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (parent != null) Text(parent.name, style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A category's subcategories, "No subcategory" first, and "+ New" to add one without leaving. Picking any of them (or a
 * new one, which [LocalAddSubcategory] creates) calls [onPick] with its id.
 */
@Composable
fun ColumnScope.SubcategoryPickerPanel(
    categories: List<Category>,
    top: Category,
    selectedId: Long?,
    wide: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val addSubcategory = LocalAddSubcategory.current
    var newName by remember(top.id) { mutableStateOf<String?>(null) }
    PanelTitle(top.name, back = onBack, onDone = onDone)
    LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "none") {
            PickTile(on = selectedId == top.id, onClick = { onPick(top.id) }) {
                Text(stringResource(R.string.common_no_subcategory), style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            }
        }
        items(subcategories(categories, top), key = { it.id }) { sub ->
            PickTile(on = selectedId == sub.id, onClick = { onPick(sub.id) }) {
                Text(sub.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item(key = "new", span = { GridItemSpan(if (newName == null) 1 else maxLineSpan) }) {
            val typed = newName
            if (typed == null) PickTile(on = false, onClick = { newName = "" }) {
                Text(stringResource(R.string.common_plus_new), style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            } else NewSubcategory(
                parent = top,
                value = typed,
                siblings = categories.filter { it.parentId == top.id }.map { it.name },
                onChange = { newName = it },
                onAdd = { name -> addSubcategory(top, name) { id -> newName = null; onPick(id) } },
            )
        }
    }
}

/** The field for a new subcategory's name, checked against [siblings] before [onAdd] gets the cleaned name. */
@Composable
private fun NewSubcategory(parent: Category, value: String, siblings: List<String>, onChange: (String) -> Unit, onAdd: (String) -> Unit) {
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val add: () -> Unit = {
        problem = InputRules.nameProblem(value, "subcategory", InputRules.CATEGORY_NAME_MAX,
            taken = { n -> siblings.any { it.equals(n, ignoreCase = true) } })
        if (problem == null) onAdd(InputRules.clean(value))
    }
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value, { onChange(InputRules.typed(it, InputRules.CATEGORY_NAME_MAX)); problem = null }, singleLine = true,
            placeholder = { Text(stringResource(R.string.common_new_under, parent.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            isError = problem != null,
            supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        TextButton(onClick = add, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.common_add), color = Kassa.colors.ink, style = KassaType.bodyStrong) }
    }
}

/*
 * Choosing a category inside a bottom sheet. The sheet's form is wrapped in [CategoryPickerHost]; a [CategoryChoiceRow]
 * in the form opens the picker in place of the form (with a back arrow), and picking returns to the form.
 */

/** What the picker is choosing: the sheet's title for it, what is picked now, and what to do with the answer. */
class CategoryRequest(
    val title: String,
    val kind: CategoryKind,
    val selectedId: Long?,
    /** Shown above the grid as the first answer, which hands back null ("Same as interest", the default); null for none. */
    val topOption: String?,
    val onPick: (Long?) -> Unit,
)

/** Opens the picker over the sheet's form. Provided by [CategoryPickerHost]; null outside one. */
val LocalCategoryPicker = staticCompositionLocalOf<((CategoryRequest) -> Unit)?> { null }

/**
 * The body of a bottom sheet: [content] in a scrolling column, replaced by the category picker (or, when [accounts] are
 * given, the account picker, see AccountPicker.kt) while one is open. The form's scroll position is kept while a picker
 * shows. [modifier] is applied after the scroll, like padding.
 */
@Composable
fun CategoryPickerHost(
    categories: List<Category>,
    modifier: Modifier = Modifier,
    accounts: List<app.kassa.money.domain.Account> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    var request by remember { mutableStateOf<CategoryRequest?>(null) }
    var accountRequest by remember { mutableStateOf<AccountRequest?>(null) }
    val scroll = rememberScrollState()
    val open = request
    val openAccount = accountRequest
    if (openAccount != null) AccountPickerPage(accounts, openAccount, onClose = { accountRequest = null }, modifier = modifier)
    else if (open == null) CompositionLocalProvider(
        LocalCategoryPicker provides { r -> request = r },
        LocalAccountPicker provides { r -> accountRequest = r },
    ) {
        Column(Modifier.verticalScroll(scroll).then(modifier), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    } else CategoryPickerPage(categories, open, onClose = { request = null }, modifier = modifier)
}

/** The picker as a sheet page: back arrow and title, then the same panels the entry page uses. */
@Composable
private fun CategoryPickerPage(categories: List<Category>, request: CategoryRequest, onClose: () -> Unit, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var openTop by remember { mutableStateOf<Category?>(null) }
    var selected by remember { mutableStateOf(request.selectedId) }
    val finish: (Long?) -> Unit = { id -> request.onPick(id); onClose() }
    val config = LocalConfiguration.current
    // A grid needs a height to fill, and the sheet has none of its own: most of the screen, so the keyboard still fits.
    val height = (config.screenHeightDp * 0.62f).dp
    Column(modifier.height(height), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (openTop != null) openTop = null else onClose() }) { Icon(Chevron(left = true), stringResource(R.string.common_back), tint = Kassa.colors.ink) }
            Text(request.title, style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val top = openTop
        if (top == null) {
            if (request.topOption != null && query.isBlank()) PickTile(on = selected == null, onClick = { finish(null) }) {
                Text(request.topOption, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CategoryPickerPanel(
                categories, request.kind, selected, query, { query = it }, { searching = it }, wide = config.screenWidthDp >= 600, onDone = onClose,
                onPick = { id -> finish(id) },
                onOpenSubcategories = { c -> selected = c.id; openTop = c },
            )
        } else SubcategoryPickerPanel(
            categories, top, selected, wide = config.screenWidthDp >= 600, onBack = { openTop = null }, onDone = onClose, onPick = { id -> finish(id) },
        )
    }
}

/**
 * A category choice in a form: an icon tile, the small label, the category as "Parent › Child" (or [placeholder] while none
 * is chosen), and a chevron. 56dp tall. Tapping calls [onClick].
 */
@Composable
fun CategoryFieldRow(
    label: String,
    category: Category?,
    categories: List<Category>,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replaces the category's own path as the value, for "Same as interest (Mortgage)". */
    valueText: String? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val value = valueText ?: category?.let { categoryPath(it, categories) } ?: placeholder
    val description = stringResource(R.string.common_label_value, label, value)
    val badge = category?.let { c -> c.parentId?.let { id -> categories.firstOrNull { it.id == id } } ?: c }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).border(1.dp, Kassa.colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryBadge(badge, size = 36)
        Column(Modifier.weight(1f)) {
            Text(label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
    }
}

/**
 * A [CategoryFieldRow] that picks inside a sheet (see [CategoryPickerHost]). [selectedId] null means [defaultCategory], shown
 * under [defaultText] (for example "Loan interest", or "Same as interest (Mortgage)"); with a [topOption] the picker also
 * offers that default as its first answer. [onPick] gets the chosen id, or null for the default.
 */
@Composable
fun CategoryChoiceRow(
    label: String,
    selectedId: Long?,
    categories: List<Category>,
    onPick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    kind: CategoryKind = CategoryKind.EXPENSE,
    defaultCategory: Category? = null,
    defaultText: String? = defaultCategory?.let { categoryPath(it, categories) },
    topOption: String? = null,
) {
    val open = LocalCategoryPicker.current
    val chosen = selectedId?.let { id -> categories.firstOrNull { it.id == id } }
    CategoryFieldRow(
        label, chosen ?: defaultCategory, categories, placeholder = defaultText ?: stringResource(R.string.common_choose),
        onClick = { open?.invoke(CategoryRequest(label, kind, selectedId, topOption, onPick)) },
        modifier = modifier, valueText = if (chosen == null) defaultText else null,
    )
}
