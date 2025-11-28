# Test executable
add_executable(cache_tests
    cache/murmur_hash3_test.cpp
    cache/cache_config_test.cpp
    cache/cache_metrics_test.cpp
    cache/result_cache_test.cpp
    cache/pattern_cache_test.cpp
    cache/deferred_cache_test.cpp
    cache/eviction_thread_test.cpp
    cache/cache_manager_test.cpp
    cache/integration_test.cpp
    cache/leak_test.cpp
    cache/stress_test.cpp
)

target_link_libraries(cache_tests PRIVATE
    re2_cache
    gtest
    gtest_main
)

# Discover and register tests with CTest
gtest_discover_tests(cache_tests)

# ============================================================================
# AddressSanitizer Build (for leak detection)
# ============================================================================

option(ENABLE_ASAN "Enable AddressSanitizer for leak detection" OFF)

if(ENABLE_ASAN)
    message(STATUS "AddressSanitizer ENABLED")

    target_compile_options(cache_tests PRIVATE
        -fsanitize=address
        -fsanitize=leak
        -fno-omit-frame-pointer
        -g
    )

    target_link_options(cache_tests PRIVATE
        -fsanitize=address
        -fsanitize=leak
    )

    # Also enable for cache library
    target_compile_options(re2_cache PRIVATE
        -fsanitize=address
        -fsanitize=leak
        -fno-omit-frame-pointer
        -g
    )

    target_link_options(re2_cache PRIVATE
        -fsanitize=address
        -fsanitize=leak
    )
endif()

# ============================================================================
# Thread Sanitizer Build (for race condition detection)
# ============================================================================

option(ENABLE_TSAN "Enable ThreadSanitizer for race detection" OFF)

if(ENABLE_TSAN)
    message(STATUS "ThreadSanitizer ENABLED")

    target_compile_options(cache_tests PRIVATE
        -fsanitize=thread
        -fno-omit-frame-pointer
        -g
    )

    target_link_options(cache_tests PRIVATE
        -fsanitize=thread
    )

    # Also enable for cache library
    target_compile_options(re2_cache PRIVATE
        -fsanitize=thread
        -fno-omit-frame-pointer
        -g
    )

    target_link_options(re2_cache PRIVATE
        -fsanitize=thread
    )
endif()

# Note: Cannot enable both ASan and TSan simultaneously
if(ENABLE_ASAN AND ENABLE_TSAN)
    message(FATAL_ERROR "Cannot enable both ASAN and TSAN simultaneously. Choose one.")
endif()

message(STATUS "Test configuration:")
message(STATUS "  AddressSanitizer:  ${ENABLE_ASAN}")
message(STATUS "  ThreadSanitizer:   ${ENABLE_TSAN}")
