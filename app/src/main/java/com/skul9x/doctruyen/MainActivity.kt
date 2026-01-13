package com.skul9x.doctruyen

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.skul9x.doctruyen.adapter.StoryAdapter
import com.skul9x.doctruyen.network.HostingVerifier
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.network.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var searchEditText: EditText
    private lateinit var btnClearSearch: android.widget.ImageButton
    private lateinit var btnRandomStory: MaterialButton
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnErrorRetry: MaterialButton
    
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnSort: MaterialButton
    
    private lateinit var adapter: StoryAdapter
    private var currentPage = 1
    private var totalPages = 1
    private var isLoading = false
    private var currentSearch = ""
    private var searchJob: Job? = null
    private var loadJob: Job? = null  // Track load coroutine for cancellation
    private var isVerified = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }
        
        initViews()
        setupRecyclerView()
        setupListeners()
        
        // Verify hosting first
        verifyHosting()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        emptyState = findViewById(R.id.emptyState)
        errorState = findViewById(R.id.errorState)
        errorText = findViewById(R.id.errorText)
        searchEditText = findViewById(R.id.searchEditText)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnRandomStory = findViewById(R.id.btnRandomStory)
        btnRetry = findViewById(R.id.btnRetry)
        btnErrorRetry = findViewById(R.id.btnErrorRetry)
        btnSettings = findViewById(R.id.btnSettings)
        btnSort = findViewById(R.id.btnSort)
        
        // Configure SwipeRefresh colors
        swipeRefresh.setColorSchemeResources(
            R.color.primary,
            R.color.secondary,
            R.color.accent_yellow
        )
    }
    
    private fun setupRecyclerView() {
        adapter = StoryAdapter { story ->
            openStoryDetail(story)
        }
        
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = adapter
        
        // Pagination
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val layoutManager = recyclerView.layoutManager as GridLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                
                if (!isLoading && currentPage < totalPages) {
                    if ((visibleItemCount + firstVisiblePosition) >= totalItemCount - 4) {
                        loadMoreStories()
                    }
                }
            }
        })
    }

    private fun setupListeners() {
        // ... previous listeners ...
        // Pull to refresh
        swipeRefresh.setOnRefreshListener {
            currentPage = 1
            adapter.resetAnimations()
            loadStories(refresh = true)
        }
        
        // Search with debounce
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(500) // Debounce 500ms
                    if (query != currentSearch) {
                        currentSearch = query
                        currentPage = 1
                        adapter.resetAnimations()
                        loadStories()
                    }
                }
            }
        })
        
        // Keyboard search action
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                // Cancel any in-progress load to allow new search
                loadJob?.cancel()
                isLoading = false
                
                currentSearch = searchEditText.text.toString().trim()
                currentPage = 1
                adapter.resetAnimations()
                loadStories()
                
                // Hide keyboard to show results
                hideKeyboard()
                true
            } else false
        }
        
        // Clear search
        btnClearSearch.setOnClickListener {
            searchEditText.setText("")
            // Cancel any in-progress load
            loadJob?.cancel()
            isLoading = false
            
            currentSearch = ""
            currentPage = 1
            adapter.resetAnimations()
            loadStories()
        }
        
        // Random story
        btnRandomStory.setOnClickListener {
            loadRandomStory()
        }
        
        // Retry buttons
        btnRetry.setOnClickListener { loadStories() }
        btnErrorRetry.setOnClickListener { 
            errorState.visibility = View.GONE
            loadStories() 
        }
        
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        btnSort.setOnClickListener {
            showSortDialog()
        }
    }
    
    private fun showSortDialog() {
        val sortOptions = arrayOf("🕐 Mới nhất", "📅 Cũ nhất", "🔤 Tên A-Z", "🔠 Tên Z-A")
        val sortValues = arrayOf("newest", "oldest", "a_z", "z_a")
        
        val currentSort = com.skul9x.doctruyen.utils.UserConfig.getSortOrder(this)
        val currentIndex = sortValues.indexOf(currentSort).coerceAtLeast(0)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sắp xếp truyện")
            .setSingleChoiceItems(sortOptions, currentIndex) { dialog, which ->
                val selectedSort = sortValues[which]
                com.skul9x.doctruyen.utils.UserConfig.setSortOrder(this, selectedSort)
                
                // Reload list
                currentPage = 1
                adapter.resetAnimations()
                loadStories()
                
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun verifyHosting() {
        // Load persisted cookie first
        RetrofitClient.loadCookie(this)
        
        // Show loading indicator during verification
        progressBar.visibility = View.VISIBLE
        
        // Use configured URL
        val url = com.skul9x.doctruyen.utils.UserConfig.getBaseUrl(this)
        
        // Check if cookie exists (we rely on Reactive check for expiration now)
        val hasCookie = RetrofitClient.cookie.isNotEmpty()
        
        if (hasCookie) {
            // Assume cookie is valid and try to load. 
            // If it's expired, the Interceptor will throw "FreeNfChallenge" and we catch it below.
            com.skul9x.doctruyen.utils.DebugLogger.log("MainActivity", "Cookie exists, skipping proactive verification", com.skul9x.doctruyen.utils.LogType.INFO)
            adapter.resetAnimations()
            loadStories(refresh = true)
            return
        }
        
        // Cookie missing or expired, need to verify
        com.skul9x.doctruyen.utils.DebugLogger.log("MainActivity", "Cookie expired or missing, verifying...", com.skul9x.doctruyen.utils.LogType.INFO)
        
        HostingVerifier.verify(this, url) { success ->
            isVerified = success
            runOnUiThread {
                if (success) {
                    adapter.resetAnimations()
                    loadStories(refresh = true)
                } else {
                    progressBar.visibility = View.GONE
                    showError(getString(R.string.verify_failed))
                }
            }
        }
    }
    
    private fun loadStories(refresh: Boolean = false) {
        if (isLoading) return
        isLoading = true
        
        if (!refresh && currentPage == 1) {
            progressBar.visibility = View.VISIBLE
        }
        emptyState.visibility = View.GONE
        errorState.visibility = View.GONE
        
        loadJob = lifecycleScope.launch {
            try {
                val sort = com.skul9x.doctruyen.utils.UserConfig.getSortOrder(this@MainActivity)
                
                val response = withContext(Dispatchers.IO) {
                    // Pass context to use configured URL
                    RetrofitClient.getApiService(this@MainActivity).getStories(
                        page = currentPage,
                        search = currentSearch.ifEmpty { null },
                        sort = sort
                    )
                }
                
                if (response.status == "success") {
                    totalPages = response.totalPages
                    
                    // Ensure recyclerView is visible after successful load
                    recyclerView.visibility = View.VISIBLE
                    
                    if (currentPage == 1) {
                        adapter.submitList(response.data)
                    } else {
                        val currentList = adapter.currentList.toMutableList()
                        currentList.addAll(response.data)
                        adapter.submitList(currentList)
                    }
                    
                    if (response.data.isEmpty() && currentPage == 1) {
                        emptyState.visibility = View.VISIBLE
                    }
                } else {
                    if (currentPage == 1) {
                        showError(response.message ?: getString(R.string.error_server))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine was cancelled (e.g., new search started), ignore silently
                return@launch
            } catch (e: Exception) {
                // Check for Auth/Parsing error (Cookie expired -> Server returns HTML -> GSON fails)
                // OR specific FreeNfChallenge which we threw manually
                val isAuthError = e.message == "FreeNfChallenge" || 
                                  e is com.google.gson.stream.MalformedJsonException || 
                                  e.message?.contains("Forbidden") == true ||
                                  e.message?.contains("403") == true ||
                                  e.message?.contains("html") == true

                if (isAuthError && RetrofitClient.cookie.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                         Toast.makeText(this@MainActivity, "Phiên hết hạn, đang xác thực lại...", Toast.LENGTH_SHORT).show()
                         RetrofitClient.clearCookie(this@MainActivity)
                         verifyHosting()
                    }
                    return@launch
                }

                if (currentPage == 1) {
                    showError(e.message ?: getString(R.string.error_unknown))
                } else {
                    Toast.makeText(this@MainActivity, 
                        getString(R.string.error_loading), 
                        Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }
        }
    }
    
    private fun loadMoreStories() {
        currentPage++
        loadStories()
    }
    
    private fun loadRandomStory() {
        lifecycleScope.launch {
            try {
                btnRandomStory.isEnabled = false
                
                val response = withContext(Dispatchers.IO) {
                    // Pass context
                    RetrofitClient.getApiService(this@MainActivity).getRandomStory()
                }
                
                if (response.status == "success") {
                    openStoryDetail(response.data)
                }
            } catch (e: Exception) {
                if (e.message == "FreeNfChallenge") {
                    withContext(Dispatchers.Main) {
                         Toast.makeText(this@MainActivity, "Phiên hết hạn, đang xác thực lại...", Toast.LENGTH_SHORT).show()
                         verifyHosting()
                    }
                    return@launch
                }
                Toast.makeText(this@MainActivity, 
                    getString(R.string.error_loading), 
                    Toast.LENGTH_SHORT).show()
            } finally {
                btnRandomStory.isEnabled = true
            }
        }
    }
    
    private fun showError(message: String) {
        errorState.visibility = View.VISIBLE
        errorText.text = message
        recyclerView.visibility = View.GONE
    }
    
    private fun openStoryDetail(story: Story) {
        val intent = Intent(this, StoryDetailActivity::class.java).apply {
            putExtra("story_id", story.id)
            putExtra("story_title", story.title)
            putExtra("story_image", story.image)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { view ->
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}