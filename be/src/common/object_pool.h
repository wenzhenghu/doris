// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <mutex>
#include <ranges>
#include <vector>

namespace doris {

// An ObjectPool maintains a list of C++ objects which are deallocated
// by destroying the pool.
// Thread-safe.
class ObjectPool {
public:
    ObjectPool() = default;
    ObjectPool(const ObjectPool&) = delete;
    void operator=(const ObjectPool&) = delete;
    ~ObjectPool() { clear(); }

    template <class T>
    T* add(T* t) {
        // TODO: Consider using a lock-free structure.
        std::lock_guard<std::mutex> l(_lock);
        _objects.emplace_back(Element {t, [](void* obj) { delete reinterpret_cast<T*>(obj); }});
        return t;
    }

    template <class T>
    T* add_array(T* t) {
        std::lock_guard<std::mutex> l(_lock);
        _objects.emplace_back(Element {t, [](void* obj) { delete[] reinterpret_cast<T*>(obj); }});
        return t;
    }

    void clear() {
        std::lock_guard<std::mutex> l(_lock);
        // reverse delete object to make sure the obj can
        // safe access the member object construt early by
        // object pool
        for (auto& _object : std::ranges::reverse_view(_objects)) {
            _object.delete_fn(_object.obj);
        }
        _objects.clear();
    }

    void acquire_data(ObjectPool* src) {
        _objects.insert(_objects.end(), src->_objects.begin(), src->_objects.end());
        src->_objects.clear();
    }

    uint64_t size() {
        std::lock_guard<std::mutex> l(_lock);
        return _objects.size();
    }

private:
    /// A generic deletion function pointer. Deletes its first argument.
    using DeleteFn = void (*)(void*);

    /// For each object, a pointer to the object and a function that deletes it.
    struct Element {
        void* obj = nullptr;
        DeleteFn delete_fn;
    };

    std::vector<Element> _objects;
    std::mutex _lock;
};

} // namespace doris
