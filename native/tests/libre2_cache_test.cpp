/*
 * Copyright 2025 AxonOps
 * Licensed under the Apache License, Version 2.0
 */

#include "libre2_api.h"
#include <gtest/gtest.h>
#include <thread>
#include <vector>

using namespace libre2::api;
using namespace libre2::cache;

class Libre2CacheTest : public ::testing::Test {
protected:
    void TearDown() override {
        if (isCacheInitialized()) {
            shutdownCache();
        }
    }
};

// Cache tests will go here

