/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.atomic;

import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.extensions.communication.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * DHT atomic cache near update response.
 */
public class GridNearAtomicUpdateResponse<K, V> extends GridCacheMessage<K, V> implements GridCacheDeployable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Cache message index. */
    public static final int CACHE_MSG_IDX = nextIndexId();

    /** Node ID this reply should be sent to. */
    @GridDirectTransient
    private UUID nodeId;

    /** Future version. */
    private GridCacheVersion futVer;

    /** Update error. */
    @GridDirectTransient
    private volatile IgniteCheckedException err;

    /** Serialized error. */
    private byte[] errBytes;

    /** Return value. */
    @GridDirectTransient
    private GridCacheReturn<Object> retVal;

    /** Serialized return value. */
    private byte[] retValBytes;

    /** Failed keys. */
    @GridToStringInclude
    @GridDirectTransient
    private volatile Collection<K> failedKeys;

    /** Serialized failed keys. */
    private byte[] failedKeysBytes;

    /** Keys that should be remapped. */
    @GridToStringInclude
    @GridDirectTransient
    private Collection<K> remapKeys;

    /** Serialized keys that should be remapped. */
    private byte[] remapKeysBytes;

    /** Indexes of keys for which values were generated on primary node (used if originating node has near cache). */
    @GridDirectCollection(int.class)
    private List<Integer> nearValsIdxs;

    /** Indexes of keys for which update was skipped (used if originating node has near cache). */
    @GridDirectCollection(int.class)
    private List<Integer> nearSkipIdxs;

    /** Values generated on primary node which should be put to originating node's near cache. */
    @GridToStringInclude
    @GridDirectTransient
    private List<V> nearVals;

    /** Serialized values generated on primary node which should be put to originating node's near cache. */
    @GridToStringInclude
    @GridDirectCollection(GridCacheValueBytes.class)
    private List<GridCacheValueBytes> nearValBytes;

    /** Version generated on primary node to be used for originating node's near cache update. */
    private GridCacheVersion nearVer;

    /** Near TTLs. */
    private GridLongList nearTtls;

    /** Near expire times. */
    private GridLongList nearExpireTimes;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridNearAtomicUpdateResponse() {
        // No-op.
    }

    /**
     * @param cacheId Cache ID.
     * @param nodeId Node ID this reply should be sent to.
     * @param futVer Future version.
     */
    public GridNearAtomicUpdateResponse(int cacheId, UUID nodeId, GridCacheVersion futVer) {
        this.cacheId = cacheId;
        this.nodeId = nodeId;
        this.futVer = futVer;
    }

    /** {@inheritDoc} */
    @Override public int lookupIndex() {
        return CACHE_MSG_IDX;
    }

    /**
     * @return Node ID this response should be sent to.
     */
    public UUID nodeId() {
        return nodeId;
    }

    /**
     * @param nodeId Node ID.
     */
    public void nodeId(UUID nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * @return Future version.
     */
    public GridCacheVersion futureVersion() {
        return futVer;
    }

    /**
     * @return Update error, if any.
     */
    public Throwable error() {
        return err;
    }

    /**
     * @return Collection of failed keys.
     */
    public Collection<K> failedKeys() {
        return failedKeys;
    }

    /**
     * @return Return value.
     */
    public GridCacheReturn<Object> returnValue() {
        return retVal;
    }

    /**
     * @param retVal Return value.
     */
    public void returnValue(GridCacheReturn<Object> retVal) {
        this.retVal = retVal;
    }

    /**
     * @param remapKeys Remap keys.
     */
    public void remapKeys(Collection<K> remapKeys) {
        this.remapKeys = remapKeys;
    }

    /**
     * @return Remap keys.
     */
    public Collection<K> remapKeys() {
        return remapKeys;
    }

    /**
     * Adds value to be put in near cache on originating node.
     *
     * @param keyIdx Key index.
     * @param val Value.
     * @param valBytes Value bytes.
     * @param ttl TTL for near cache update.
     * @param expireTime Expire time for near cache update.
     */
    public void addNearValue(int keyIdx,
        @Nullable V val,
        @Nullable byte[] valBytes,
        long ttl,
        long expireTime) {
        if (nearValsIdxs == null) {
            nearValsIdxs = new ArrayList<>();
            nearValBytes = new ArrayList<>();
            nearVals = new ArrayList<>();
        }

        addNearTtl(keyIdx, ttl, expireTime);

        nearValsIdxs.add(keyIdx);
        nearVals.add(val);
        nearValBytes.add(valBytes != null ? GridCacheValueBytes.marshaled(valBytes) : null);
    }

    /**
     * @param keyIdx Key index.
     * @param ttl TTL for near cache update.
     * @param expireTime Expire time for near cache update.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public void addNearTtl(int keyIdx, long ttl, long expireTime) {
        if (ttl >= 0) {
            if (nearTtls == null) {
                nearTtls = new GridLongList(16);

                for (int i = 0; i < keyIdx; i++)
                    nearTtls.add(-1L);
            }
        }

        if (nearTtls != null)
            nearTtls.add(ttl);

        if (expireTime >= 0) {
            if (nearExpireTimes == null) {
                nearExpireTimes = new GridLongList(16);

                for (int i = 0; i < keyIdx; i++)
                    nearExpireTimes.add(-1);
            }
        }

        if (nearExpireTimes != null)
            nearExpireTimes.add(expireTime);
    }

    /**
     * @param idx Index.
     * @return Expire time for near cache update.
     */
    public long nearExpireTime(int idx) {
        if (nearExpireTimes != null) {
            assert idx >= 0 && idx < nearExpireTimes.size();

            return nearExpireTimes.get(idx);
        }

        return -1L;
    }

    /**
     * @param idx Index.
     * @return TTL for near cache update.
     */
    public long nearTtl(int idx) {
        if (nearTtls != null) {
            assert idx >= 0 && idx < nearTtls.size();

            return nearTtls.get(idx);
        }

        return -1L;
    }

    /**
     * @param nearVer Version generated on primary node to be used for originating node's near cache update.
     */
    public void nearVersion(GridCacheVersion nearVer) {
        this.nearVer = nearVer;
    }

    /**
     * @return Version generated on primary node to be used for originating node's near cache update.
     */
    public GridCacheVersion nearVersion() {
        return nearVer;
    }

    /**
     * @param keyIdx Index of key for which update was skipped
     */
    public void addSkippedIndex(int keyIdx) {
        if (nearSkipIdxs == null)
            nearSkipIdxs = new ArrayList<>();

        nearSkipIdxs.add(keyIdx);

        addNearTtl(keyIdx, -1L, -1L);
    }

    /**
     * @return Indexes of keys for which update was skipped
     */
    @Nullable public List<Integer> skippedIndexes() {
        return nearSkipIdxs;
    }

    /**
     * @return Indexes of keys for which values were generated on primary node.
     */
   @Nullable public List<Integer> nearValuesIndexes() {
        return nearValsIdxs;
   }

    /**
     * @param idx Index.
     * @return Value generated on primary node which should be put to originating node's near cache.
     */
    @Nullable public V nearValue(int idx) {
        return nearVals.get(idx);
    }

    /**
     * @param idx Index.
     * @return Serialized value generated on primary node which should be put to originating node's near cache.
     */
    @Nullable public byte[] nearValueBytes(int idx) {
        if (nearValBytes != null) {
            GridCacheValueBytes valBytes0 = nearValBytes.get(idx);

            if (valBytes0 != null && !valBytes0.isPlain())
                return valBytes0.get();
        }

        return null;
    }

    /**
     * Adds key to collection of failed keys.
     *
     * @param key Key to add.
     * @param e Error cause.
     */
    public synchronized void addFailedKey(K key, Throwable e) {
        if (failedKeys == null)
            failedKeys = new ConcurrentLinkedQueue<>();

        failedKeys.add(key);

        if (err == null)
            err = new IgniteCheckedException("Failed to update keys on primary node.");

        err.addSuppressed(e);
    }

    /**
     * Adds keys to collection of failed keys.
     *
     * @param keys Key to add.
     * @param e Error cause.
     */
    public synchronized void addFailedKeys(Collection<K> keys, Throwable e) {
        if (failedKeys == null)
            failedKeys = new ArrayList<>(keys.size());

        failedKeys.addAll(keys);

        if (err == null)
            err = new IgniteCheckedException("Failed to update keys on primary node.");

        err.addSuppressed(e);
    }

    /** {@inheritDoc}
     * @param ctx*/
    @Override public void prepareMarshal(GridCacheSharedContext<K, V> ctx) throws IgniteCheckedException {
        super.prepareMarshal(ctx);

        if (err != null)
            errBytes = ctx.marshaller().marshal(err);

        if (retVal != null)
            retValBytes = ctx.marshaller().marshal(retVal);

        if (failedKeys != null)
            failedKeysBytes = ctx.marshaller().marshal(failedKeys);

        if (remapKeys != null)
            remapKeysBytes = ctx.marshaller().marshal(remapKeys);

        nearValBytes = marshalValuesCollection(nearVals, ctx);
    }

    /** {@inheritDoc} */
    @Override public void finishUnmarshal(GridCacheSharedContext<K, V> ctx, ClassLoader ldr) throws IgniteCheckedException {
        super.finishUnmarshal(ctx, ldr);

        if (errBytes != null)
            err = ctx.marshaller().unmarshal(errBytes, ldr);

        if (retValBytes != null)
            retVal = ctx.marshaller().unmarshal(retValBytes, ldr);

        if (failedKeysBytes != null)
            failedKeys = ctx.marshaller().unmarshal(failedKeysBytes, ldr);

        if (remapKeysBytes != null)
            remapKeys = ctx.marshaller().unmarshal(remapKeysBytes, ldr);

        nearVals = unmarshalValueBytesCollection(nearValBytes, ctx, ldr);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"CloneDoesntCallSuperClone", "CloneCallsConstructors"})
    @Override public MessageAdapter clone() {
        GridNearAtomicUpdateResponse _clone = new GridNearAtomicUpdateResponse();

        clone0(_clone);

        return _clone;
    }

    /** {@inheritDoc} */
    @Override protected void clone0(MessageAdapter _msg) {
        super.clone0(_msg);

        GridNearAtomicUpdateResponse _clone = (GridNearAtomicUpdateResponse)_msg;

        _clone.nodeId = nodeId;
        _clone.futVer = futVer;
        _clone.err = err;
        _clone.errBytes = errBytes;
        _clone.retVal = retVal;
        _clone.retValBytes = retValBytes;
        _clone.failedKeys = failedKeys;
        _clone.failedKeysBytes = failedKeysBytes;
        _clone.remapKeys = remapKeys;
        _clone.remapKeysBytes = remapKeysBytes;
        _clone.nearValsIdxs = nearValsIdxs;
        _clone.nearSkipIdxs = nearSkipIdxs;
        _clone.nearVals = nearVals;
        _clone.nearValBytes = nearValBytes;
        _clone.nearVer = nearVer;
        _clone.nearTtls = nearTtls;
        _clone.nearExpireTimes = nearExpireTimes;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("fallthrough")
    @Override public boolean writeTo(ByteBuffer buf) {
        commState.setBuffer(buf);

        if (!super.writeTo(buf))
            return false;

        if (!commState.typeWritten) {
            if (!commState.putByte(null, directType()))
                return false;

            commState.typeWritten = true;
        }

        switch (commState.idx) {
            case 3:
                if (!commState.putByteArray("errBytes", errBytes))
                    return false;

                commState.idx++;

            case 4:
                if (!commState.putByteArray("failedKeysBytes", failedKeysBytes))
                    return false;

                commState.idx++;

            case 5:
                if (!commState.putCacheVersion("futVer", futVer))
                    return false;

                commState.idx++;

            case 6:
                if (!commState.putLongList("nearExpireTimes", nearExpireTimes))
                    return false;

                commState.idx++;

            case 7:
                if (!commState.putLongList("nearTtls", nearTtls))
                    return false;

                commState.idx++;

            case 8:
                if (!commState.putByteArray("remapKeysBytes", remapKeysBytes))
                    return false;

                commState.idx++;

            case 9:
                if (!commState.putByteArray("retValBytes", retValBytes))
                    return false;

                commState.idx++;

            case 10:
                if (nearSkipIdxs != null) {
                    if (commState.it == null) {
                        if (!commState.putInt(null, nearSkipIdxs.size()))
                            return false;

                        commState.it = nearSkipIdxs.iterator();
                    }

                    while (commState.it.hasNext() || commState.cur != NULL) {
                        if (commState.cur == NULL)
                            commState.cur = commState.it.next();

                        if (!commState.putInt(null, (int)commState.cur))
                            return false;

                        commState.cur = NULL;
                    }

                    commState.it = null;
                } else {
                    if (!commState.putInt(null, -1))
                        return false;
                }

                commState.idx++;

            case 11:
                if (nearValBytes != null) {
                    if (commState.it == null) {
                        if (!commState.putInt(null, nearValBytes.size()))
                            return false;

                        commState.it = nearValBytes.iterator();
                    }

                    while (commState.it.hasNext() || commState.cur != NULL) {
                        if (commState.cur == NULL)
                            commState.cur = commState.it.next();

                        if (!commState.putValueBytes(null, (GridCacheValueBytes)commState.cur))
                            return false;

                        commState.cur = NULL;
                    }

                    commState.it = null;
                } else {
                    if (!commState.putInt(null, -1))
                        return false;
                }

                commState.idx++;

            case 12:
                if (nearValsIdxs != null) {
                    if (commState.it == null) {
                        if (!commState.putInt(null, nearValsIdxs.size()))
                            return false;

                        commState.it = nearValsIdxs.iterator();
                    }

                    while (commState.it.hasNext() || commState.cur != NULL) {
                        if (commState.cur == NULL)
                            commState.cur = commState.it.next();

                        if (!commState.putInt(null, (int)commState.cur))
                            return false;

                        commState.cur = NULL;
                    }

                    commState.it = null;
                } else {
                    if (!commState.putInt(null, -1))
                        return false;
                }

                commState.idx++;

            case 13:
                if (!commState.putCacheVersion("nearVer", nearVer))
                    return false;

                commState.idx++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("fallthrough")
    @Override public boolean readFrom(ByteBuffer buf) {
        commState.setBuffer(buf);

        if (!super.readFrom(buf))
            return false;

        switch (commState.idx) {
            case 3:
                errBytes = commState.getByteArray("errBytes");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 4:
                failedKeysBytes = commState.getByteArray("failedKeysBytes");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 5:
                futVer = commState.getCacheVersion("futVer");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 6:
                nearExpireTimes = commState.getLongList("nearExpireTimes");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 7:
                nearTtls = commState.getLongList("nearTtls");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 8:
                remapKeysBytes = commState.getByteArray("remapKeysBytes");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 9:
                retValBytes = commState.getByteArray("retValBytes");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

            case 10:
                if (commState.readSize == -1) {
                    int _val = commState.getInt(null);

                    if (!commState.lastRead())
                        return false;
                    commState.readSize = _val;
                }

                if (commState.readSize >= 0) {
                    if (nearSkipIdxs == null)
                        nearSkipIdxs = new ArrayList<>(commState.readSize);

                    for (int i = commState.readItems; i < commState.readSize; i++) {
                        int _val = commState.getInt(null);

                        if (!commState.lastRead())
                            return false;

                        nearSkipIdxs.add((Integer)_val);

                        commState.readItems++;
                    }
                }

                commState.readSize = -1;
                commState.readItems = 0;

                commState.idx++;

            case 11:
                if (commState.readSize == -1) {
                    int _val = commState.getInt(null);

                    if (!commState.lastRead())
                        return false;
                    commState.readSize = _val;
                }

                if (commState.readSize >= 0) {
                    if (nearValBytes == null)
                        nearValBytes = new ArrayList<>(commState.readSize);

                    for (int i = commState.readItems; i < commState.readSize; i++) {
                        GridCacheValueBytes _val = commState.getValueBytes(null);

                        if (!commState.lastRead())
                            return false;

                        nearValBytes.add((GridCacheValueBytes)_val);

                        commState.readItems++;
                    }
                }

                commState.readSize = -1;
                commState.readItems = 0;

                commState.idx++;

            case 12:
                if (commState.readSize == -1) {
                    int _val = commState.getInt(null);

                    if (!commState.lastRead())
                        return false;
                    commState.readSize = _val;
                }

                if (commState.readSize >= 0) {
                    if (nearValsIdxs == null)
                        nearValsIdxs = new ArrayList<>(commState.readSize);

                    for (int i = commState.readItems; i < commState.readSize; i++) {
                        int _val = commState.getInt(null);

                        if (!commState.lastRead())
                            return false;

                        nearValsIdxs.add((Integer)_val);

                        commState.readItems++;
                    }
                }

                commState.readSize = -1;
                commState.readItems = 0;

                commState.idx++;

            case 13:
                nearVer = commState.getCacheVersion("nearVer");

                if (!commState.lastRead())
                    return false;

                commState.idx++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public byte directType() {
        return 41;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridNearAtomicUpdateResponse.class, this, "parent");
    }
}
