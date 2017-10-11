/*
 * AsyncStackTester.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2013-2018 Apple Inc. and the FoundationDB project authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.cie.foundationdb.test;

import java.math.BigInteger;
import java.util.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.apple.cie.foundationdb.Cluster;
import com.apple.cie.foundationdb.Database;
import com.apple.cie.foundationdb.FDB;
import com.apple.cie.foundationdb.FDBException;
import com.apple.cie.foundationdb.KeySelector;
import com.apple.cie.foundationdb.KeyValue;
import com.apple.cie.foundationdb.MutationType;
import com.apple.cie.foundationdb.Range;
import com.apple.cie.foundationdb.StreamingMode;
import com.apple.cie.foundationdb.Transaction;
import com.apple.cie.foundationdb.async.AsyncUtil;
import com.apple.cie.foundationdb.tuple.ByteArrayUtil;
import com.apple.cie.foundationdb.tuple.Tuple;

public class AsyncStackTester {
	static final String DIRECTORY_PREFIX = "DIRECTORY_";

	static class WaitEmpty implements Function<Transaction, CompletableFuture<Void>> {
		private final byte[] prefix;
		WaitEmpty(byte[] prefix) {
			this.prefix = prefix;
		}

		@Override
		public CompletableFuture<Void> apply(Transaction tr) {
			return tr.getRange(Range.startsWith(prefix)).asList().thenApplyAsync(new Function<List<KeyValue>, Void>() {
				@Override
				public Void apply(List<KeyValue> list) {
					if(list.size() > 0) {
						//System.out.println(" - Throwing new fake commit error...");
						throw new FDBException("ERROR: Fake commit conflict", 1020);
					}
					return null;
				}
			});
		}
	}

	static CompletableFuture<Void> processInstruction(final Instruction inst) {
		StackOperation op = StackOperation.valueOf(inst.op);
		if(op == StackOperation.PUSH) {
			Object item = inst.tokens.get(1);
			inst.push(item);
			/*if(item instanceof byte[])
				System.out.println(inst.context.preStr + " - " + "Pushing '" + ByteArrayUtil.printable((byte[]) item) + "'");
			else if(item instanceof Number)
				System.out.println(inst.context.preStr + " - " + "Pushing " + ((Number)item).longValue());
			else if(item instanceof String)
				System.out.println(inst.context.preStr + " - " + "Pushing (utf8) '" + item.toString() + "'");
			else if(item == null)
				System.out.println(inst.context.preStr + " - " + "Pushing null");
			else
				System.out.println(inst.context.preStr + " - " + "Pushing item of type " + item.getClass().getName());*/
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.POP) {
			inst.pop();
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.DUP) {
			if(inst.size() == 0)
				throw new RuntimeException("No stack bro!! (" + inst.context.preStr + ")");
			StackEntry e = inst.pop();
			inst.push(e);
			inst.push(e);
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.EMPTY_STACK) {
			inst.clear();
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.SWAP) {
			return inst.popParam()
			.thenApplyAsync(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					int index = StackUtils.getInt(param);
					if(index >= inst.size())
						throw new IllegalArgumentException("Stack index not valid");

					inst.swap(index);
					return null;
				}
			});
		}
		else if(op == StackOperation.WAIT_FUTURE) {
			return popAndWait(inst)
			.thenApplyAsync(new Function<StackEntry, Void>() {
				@Override
				public Void apply(StackEntry e) {
					inst.push(e);
					return null; 
				}
			});
		}
		else if(op == StackOperation.WAIT_EMPTY) {
			return inst.popParam()
			.thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(Object param) {
					WaitEmpty retryable = new WaitEmpty((byte[])param);
					return inst.context.db.runAsync(retryable).thenApply(new Function<Void, Void>() {
						@Override
						public Void apply(Void o) {
							inst.push( "WAITED_FOR_EMPTY".getBytes());
							return null;
						}
					});
				}
			});
		}
		else if(op == StackOperation.START_THREAD) {
			return inst.popParam()
			.thenApplyAsync(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					//System.out.println(inst.context.preStr + " - " + "Starting new thread at prefix: " + ByteArrayUtil.printable((byte[]) params.get(0)));
					inst.context.addContext((byte[])param);
					return null;
				}
			});
		}
		else if(op == StackOperation.NEW_TRANSACTION) {
			inst.context.newTransaction();
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.USE_TRANSACTION) {
			return inst.popParam()
			.thenApplyAsync(new Function<Object, Void>() {
				public Void apply(Object param) {
					inst.context.switchTransaction((byte[])param);
					return null;
				}
			});
		}
		else if(op == StackOperation.SET) {
			return inst.popParams(2).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(final List<Object> params) {
					/*System.out.println(inst.context.preStr + " - " + "Setting '" + ByteArrayUtil.printable((byte[]) params.get(0)) +
							"' to '" + ByteArrayUtil.printable((byte[]) params.get(1)) + "'"); */
					return executeMutation(inst, new Function<Transaction, CompletableFuture<Void>>() {
						@Override
						public CompletableFuture<Void> apply(Transaction tr) {
							tr.set((byte[])params.get(0), (byte[])params.get(1));
							return CompletableFuture.completedFuture(null);
						}
					});
				}
			});
		}
		else if(op == StackOperation.CLEAR) {
			return inst.popParam().thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(final Object param) {
					//System.out.println(inst.context.preStr + " - " + "Clearing: '" + ByteArrayUtil.printable((byte[])param) + "'");
					return executeMutation(inst, new Function<Transaction, CompletableFuture<Void>>() {
						@Override
						public CompletableFuture<Void> apply(Transaction tr) {
							tr.clear((byte[])param);
							return CompletableFuture.completedFuture(null);
						}
					});
				}
			});
		}
		else if(op == StackOperation.CLEAR_RANGE) {
			return inst.popParams(2).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(final List<Object> params) {
					return executeMutation(inst, new Function<Transaction, CompletableFuture<Void>>() {
						@Override
						public CompletableFuture<Void> apply(Transaction tr) {
							tr.clear((byte[])params.get(0), (byte[])params.get(1));
							return CompletableFuture.completedFuture(null);
						}
					});
				}
			});
		}
		else if(op == StackOperation.CLEAR_RANGE_STARTS_WITH) {
			return inst.popParam().thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(final Object param) {
					return executeMutation(inst, new Function<Transaction, CompletableFuture<Void>>() {
						@Override
						public CompletableFuture<Void> apply(Transaction tr) {
							tr.clear(Range.startsWith((byte[])param));
							return CompletableFuture.completedFuture(null);
						}
					});
				}
			});
		}
		else if(op == StackOperation.ATOMIC_OP) {
			return inst.popParams(3).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(final List<Object> params) {
					final MutationType optype = MutationType.valueOf((String)params.get(0));
					return executeMutation(inst,
						new Function<Transaction, CompletableFuture<Void>>() {
							@Override
							public CompletableFuture<Void> apply(Transaction tr) {
								tr.mutate(optype, (byte[])params.get(1), (byte[])params.get(2));
								return CompletableFuture.completedFuture(null);
							}
						}
					);
				}
			});
		}
		else if(op == StackOperation.COMMIT) {
			inst.push(inst.tr.commit());
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.RESET) {
			inst.context.newTransaction();
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.CANCEL) {
			inst.tr.cancel();
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.READ_CONFLICT_RANGE) {
			return inst.popParams(2).thenApplyAsync(new Function<List<Object>, Void>() {
				@Override
				public Void apply(List<Object> params) {
					inst.tr.addReadConflictRange((byte[])params.get(0), (byte[])params.get(1));
					inst.push("SET_CONFLICT_RANGE".getBytes());
					return null; 
				}
			});
		}
		else if(op == StackOperation.WRITE_CONFLICT_RANGE) {
			return inst.popParams(2).thenApplyAsync(new Function<List<Object>, Void>() {
				@Override
				public Void apply(List<Object> params) {
					inst.tr.addWriteConflictRange((byte[])params.get(0), (byte[])params.get(1));
					inst.push("SET_CONFLICT_RANGE".getBytes());
					return null;
				}
			});
		}
		else if(op == StackOperation.READ_CONFLICT_KEY) {
			return inst.popParam().thenApplyAsync(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					inst.tr.addReadConflictKey((byte[])param);
					inst.push("SET_CONFLICT_KEY".getBytes());
					return null;
				}
			});
		}
		else if(op == StackOperation.WRITE_CONFLICT_KEY) {
			return inst.popParam().thenApplyAsync(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					inst.tr.addWriteConflictKey((byte[])param);
					inst.push("SET_CONFLICT_KEY".getBytes());
					return null;
				}
			});
		}
		else if(op == StackOperation.DISABLE_WRITE_CONFLICT) {
			inst.tr.options().setNextWriteNoWriteConflictRange();
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.GET) {
			return inst.popParam().thenApplyAsync(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					inst.push(inst.readTcx.readAsync(readTr -> readTr.get((byte[]) param)));
					return null;
				}
			});
		}
		else if(op == StackOperation.GET_RANGE) {
			return inst.popParams(5).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(List<Object> params) {
					int limit = StackUtils.getInt(params.get(2));
					boolean reverse = StackUtils.getBoolean(params.get(3));
					StreamingMode mode = inst.context.streamingModeFromCode(
							StackUtils.getInt(params.get(4), StreamingMode.ITERATOR.code()));

					CompletableFuture<List<KeyValue>> range = inst.readTcx.readAsync(readTr -> readTr.getRange((byte[])params.get(0), (byte[])params.get(1), limit, reverse, mode).asList());
					return pushRange(inst, range);
				}
			});
		}
		else if(op == StackOperation.GET_RANGE_SELECTOR) {
			return inst.popParams(10).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(List<Object> params) {
					int limit = StackUtils.getInt(params.get(6));
					boolean reverse = StackUtils.getBoolean(params.get(7));
					StreamingMode mode = inst.context.streamingModeFromCode(
							StackUtils.getInt(params.get(8), StreamingMode.ITERATOR.code()));

					KeySelector start = StackUtils.createSelector(params.get(0),params.get(1), params.get(2));
					KeySelector end = StackUtils.createSelector(params.get(3), params.get(4), params.get(5));

					CompletableFuture<List<KeyValue>> range = inst.readTcx.readAsync(readTr -> readTr.getRange(start, end, limit, reverse, mode).asList());
					return pushRange(inst, range, (byte[])params.get(9));
				}
			});
		}
		else if(op == StackOperation.GET_RANGE_STARTS_WITH) {
			return inst.popParams(4).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(List<Object> params) {
					int limit = StackUtils.getInt(params.get(1));
					boolean reverse = StackUtils.getBoolean(params.get(2));
					StreamingMode mode = inst.context.streamingModeFromCode(
							StackUtils.getInt(params.get(3), StreamingMode.ITERATOR.code()));

					CompletableFuture<List<KeyValue>> range = inst.readTcx.readAsync(readTr -> readTr.getRange(Range.startsWith((byte[])params.get(0)), limit, reverse, mode).asList());
					return pushRange(inst, range);
				}
			});
		}
		else if(op == StackOperation.GET_KEY) {
			return inst.popParams(4).thenApplyAsync(new Function<List<Object>, Void>() {
				@Override
				public Void apply(List<Object> params) {
					KeySelector start = StackUtils.createSelector(params.get(0),params.get(1), params.get(2));
					inst.push(inst.readTcx.readAsync(readTr -> executeGetKey(readTr.getKey(start), (byte[])params.get(3))));
					return null;
				}
			});
		}
		else if(op == StackOperation.GET_READ_VERSION) {
			return inst.readTr.getReadVersion().thenApplyAsync(new Function<Long, Void>() {
				@Override
				public Void apply(Long readVersion) {
					inst.context.lastVersion = readVersion;
					inst.push("GOT_READ_VERSION".getBytes());
					return null;
				}
			});
		}
		else if(op == StackOperation.GET_COMMITTED_VERSION) {
			try {
				inst.context.lastVersion = inst.tr.getCommittedVersion();
				inst.push("GOT_COMMITTED_VERSION".getBytes());
			}
			catch(FDBException e) {
				StackUtils.pushError(inst, e);
			}

			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.GET_VERSIONSTAMP) {
			try {
				inst.push(inst.tr.getVersionstamp());
			}
			catch(FDBException e) {
				StackUtils.pushError(inst, e);
			}

			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.SET_READ_VERSION) {
			if(inst.context.lastVersion == null)
				throw new IllegalArgumentException("Read version has not been read");
			inst.tr.setReadVersion(inst.context.lastVersion);
			return CompletableFuture.completedFuture(null);
		}
		else if(op == StackOperation.ON_ERROR) {
			return inst.popParam().thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(Object param) {
					int errorCode = StackUtils.getInt(param);

					// 1102 (future_released) and 2015 (future_not_set) are not errors to Java.
					//  This is never encountered by user code, so we have to do something rather
					//  messy here to get compatibility with other languages.
					//
					// First, try on error with a retryable error. If it fails, then the transaction is in
					//  a failed state and we should rethrow the error. Otherwise, throw the original error.
					boolean filteredError = errorCode == 1102 || errorCode == 2015;

					FDBException err = new FDBException("Fake testing error", filteredError ? 1020 : errorCode);
					final Transaction oldTr = inst.tr;
					CompletableFuture<Void> f = oldTr.onError(err)
						.whenComplete((tr, t) -> {
							if(t != null) {
								inst.context.newTransaction(oldTr); // Other bindings allow reuse of non-retryable transactions, so we need to emulate that behavior.
							}
							else {
								inst.context.updateCurrentTransaction(oldTr, tr);
							}
						})
						.thenApply(v -> null);

					if(filteredError) {
						f.join();
						throw new FDBException("Fake testing error", errorCode);
					}

					inst.push(f);
					return CompletableFuture.completedFuture(null);
				}
			});
		}
		else if(op == StackOperation.SUB) {
			return inst.popParams(2).thenApplyAsync(new Function<List<Object>, Void>() {
				@Override
				public Void apply(List<Object> params) {
					BigInteger result = StackUtils.getBigInteger(params.get(0)).subtract(
							StackUtils.getBigInteger(params.get(1))
					);
					inst.push(result);
					return null;
				}
			});
		}
		else if(op == StackOperation.CONCAT) {
			return inst.popParams(2).thenApplyAsync(new Function<List<Object>, Void>() {
				@Override
				public Void apply(List<Object> params) {
					if(params.get(0) instanceof String) {
						inst.push((String)params.get(0) + (String)params.get(1));
					}
					else {
						inst.push(ByteArrayUtil.join((byte[])params.get(0), (byte[])params.get(1)));
					}

					return null;
				}
			});
		}
		else if(op == StackOperation.TUPLE_PACK) {
			return inst.popParam().thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(Object param) {
					int tupleSize = StackUtils.getInt(param);
					//System.out.println(inst.context.preStr + " - " + "Packing top " + tupleSize + " items from stack");
					return inst.popParams(tupleSize).thenApplyAsync(new Function<List<Object>, Void>() {
						@Override
						public Void apply(List<Object> elements) {
							byte[] coded = Tuple.fromItems(elements).pack();
							//System.out.println(inst.context.preStr + " - " + " -> result '" + ByteArrayUtil.printable(coded) + "'");
							inst.push(coded);
							return null;
						}
					});
				}
			});
		}
		else if(op == StackOperation.TUPLE_PACK_WITH_VERSIONSTAMP) {
			return inst.popParams(2).thenComposeAsync(new Function<List<Object>, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(List<Object> params) {
				    byte[] prefix = (byte[])params.get(0);
					int tupleSize = StackUtils.getInt(params.get(1));
					//System.out.println(inst.context.preStr + " - " + "Packing top " + tupleSize + " items from stack");
					return inst.popParams(tupleSize).thenApplyAsync(new Function<List<Object>, Void>() {
						@Override
						public Void apply(List<Object> elements) {
							Tuple tuple = Tuple.fromItems(elements);
							if(!tuple.hasIncompleteVersionstamp() && Math.random() < 0.5) {
								inst.push("ERROR: NONE".getBytes());
								return null;
							}
							try {
								byte[] coded = tuple.packWithVersionstamp(prefix);
								inst.push("OK".getBytes());
								inst.push(coded);
							} catch(IllegalArgumentException e) {
								if(e.getMessage().startsWith("No incomplete")) {
									inst.push("ERROR: NONE".getBytes());
								} else {
									inst.push("ERROR: MULTIPLE".getBytes());
								}
							}
							return null;
						}
					});
				}
			});
		}
		else if(op == StackOperation.TUPLE_UNPACK) {
			return inst.popParam().thenApplyAsync(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					/*System.out.println(inst.context.preStr + " - " + "Unpacking tuple code: " +
							ByteArrayUtil.printable((byte[]) param)); */
					Tuple t = Tuple.fromBytes((byte[])param);
					for(Object o : t.getItems()) {
						byte[] itemBytes = Tuple.from(o).pack();
						inst.push(itemBytes);
					}
					return null;
				}
			});
		}
		else if(op == StackOperation.TUPLE_RANGE) {
			return inst.popParam().thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(Object param) {
					int tupleSize = StackUtils.getInt(param);
					//System.out.println(inst.context.preStr + " - " + "Tuple range with top " + tupleSize + " items from stack");
					return inst.popParams(tupleSize).thenApplyAsync(new Function<List<Object>, Void>() {
						@Override
						public Void apply(List<Object> elements) {
							Range range = Tuple.fromItems(elements).range();
							inst.push(range.begin);
							inst.push(range.end);
							return null;
						}
					});
				}
			});
		}
		else if(op == StackOperation.TUPLE_SORT) {
			return inst.popParam().thenComposeAsync(new Function<Object, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(Object param) {
					final int listSize = StackUtils.getInt(param);
					return inst.popParams(listSize).thenApply(new Function<List<Object>, Void>() {
						@Override
						public Void apply(List<Object> rawElements) {
							List<Tuple> tuples = new ArrayList(listSize);
							for(Object o : rawElements) {
								tuples.add(Tuple.fromBytes((byte[])o));
							}
							Collections.sort(tuples);
							for(Tuple t : tuples) {
								inst.push(t.pack());
							}
							return null;
						}
					});
				}
			});
		}
		else if (op == StackOperation.ENCODE_FLOAT) {
			return inst.popParam().thenApply(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					byte[] fBytes = (byte[])param;
					float value = ByteBuffer.wrap(fBytes).order(ByteOrder.BIG_ENDIAN).getFloat();
					inst.push(value);
					return null;
				}
			});
		}
		else if (op == StackOperation.ENCODE_DOUBLE) {
			return inst.popParam().thenApply(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					byte[] dBytes = (byte[])param;
					double value = ByteBuffer.wrap(dBytes).order(ByteOrder.BIG_ENDIAN).getDouble();
					inst.push(value);
					return null;
				}
			});
		}
		else if (op == StackOperation.DECODE_FLOAT) {
			return inst.popParam().thenApply(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					float value = ((Number)param).floatValue();
					inst.push(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value).array());
					return null;
				}
			});
		}
		else if (op == StackOperation.DECODE_DOUBLE) {
			return inst.popParam().thenApply(new Function<Object, Void>() {
				@Override
				public Void apply(Object param) {
					double value = ((Number)param).doubleValue();
					inst.push(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array());
					return null;
				}
			});
		}
		else if(op == StackOperation.UNIT_TESTS) {
			inst.context.db.options().setLocationCacheSize(100001);
			return inst.context.db.runAsync(tr -> {
				tr.options().setPrioritySystemImmediate();
				tr.options().setPriorityBatch();
				tr.options().setCausalReadRisky();
				tr.options().setCausalWriteRisky();
				tr.options().setReadYourWritesDisable();
				tr.options().setReadAheadDisable();
				tr.options().setReadSystemKeys();
				tr.options().setAccessSystemKeys();
				tr.options().setDurabilityDevNullIsWebScale();
				tr.options().setTimeout(60*1000);
				tr.options().setRetryLimit(50);
				tr.options().setMaxRetryDelay(100);
				tr.options().setUsedDuringCommitProtectionDisable();
				tr.options().setTransactionLoggingEnable("my_transaction");

				if(!(new FDBException("Fake", 1020)).isRetryable() ||
						(new FDBException("Fake", 10)).isRetryable())
					throw new RuntimeException("Unit test failed: Error predicate incorrect");

				byte[] test = {(byte)0xff};
				return tr.get(test).thenRunAsync(() -> {});
			}).exceptionally(t -> {
				throw new RuntimeException("Unit tests failed: " + t.getMessage());
			});
		}
		else if(op == StackOperation.LOG_STACK) {
			return inst.popParam().thenComposeAsync(prefix -> doLogStack(inst, (byte[])prefix));
		}

		throw new IllegalArgumentException("Unrecognized (or unimplemented) operation");
	}

	private static CompletableFuture<Void> executeMutation(final Instruction inst, Function<Transaction, CompletableFuture<Void>> r) {
		// run this with a retry loop
		return inst.tcx.runAsync(r).thenApplyAsync(new Function<Void, Void>() {
			@Override
			public Void apply(Void a) {
				if(inst.isDatabase)
					inst.push("RESULT_NOT_PRESENT".getBytes());
				return null;
			}
		});
	}

	private static CompletableFuture<byte[]> executeGetKey(final CompletableFuture<byte[]> keyFuture, final byte[] prefixFilter) {
		return keyFuture.thenApplyAsync(new Function<byte[], byte[]>() {
			@Override
			public byte[] apply(byte[] key) {
				if(ByteArrayUtil.startsWith(key, prefixFilter)) {
					return key;
				}
				else if(ByteArrayUtil.compareUnsigned(key, prefixFilter) < 0) { 
					return prefixFilter;
				}
				else {
					return ByteArrayUtil.strinc(prefixFilter);
				}
			}
		});
	}

	private static CompletableFuture<Void> doLogStack(final Instruction inst, final byte[] prefix) {
		Map<Integer, StackEntry> entries = new HashMap<>();
		while(inst.size() > 0) {
			entries.put(inst.size() - 1, inst.pop());
			if(entries.size() == 100) {
				return logStack(inst.context.db, entries, prefix).thenComposeAsync(v -> doLogStack(inst, prefix));
			}
		}

		return logStack(inst.context.db, entries, prefix);
	}

	private static CompletableFuture<Void> logStack(final Database db, final Map<Integer, StackEntry> entries, final byte[] prefix) {
		return db.runAsync(tr -> {
            for(Map.Entry<Integer, StackEntry> it : entries.entrySet()) {
                byte[] pk = Tuple.from(it.getKey(), it.getValue().idx).pack(prefix);
                byte[] pv = Tuple.from(StackUtils.serializeFuture(it.getValue().value)).pack();
                tr.set(pk, pv.length < 40000 ? pv : Arrays.copyOfRange(pv, 0, 40000));
            }

            return CompletableFuture.completedFuture(null);
		});
	}
	private static CompletableFuture<Void> logStack(final Instruction inst, final byte[] prefix, int i) {
		//System.out.println("Logging stack at " + i);
		while(inst.size() > 0) {
			StackEntry e = inst.pop();
			byte[] pk = Tuple.from(i, e.idx).pack(prefix);
			byte[] pv = Tuple.from(StackUtils.serializeFuture(e.value)).pack();
			inst.tr.set(pk, pv.length < 40000 ? pv : Arrays.copyOfRange(pv, 0, 40000));
			i--;
			if(i % 100 == 0) {
				final int saved = i;
				return inst.tr.commit().thenComposeAsync(new Function<Void, CompletableFuture<Void>>() {
					@Override
					public CompletableFuture<Void> apply(Void o) {
						inst.tr = inst.context.newTransaction();
						return logStack(inst, prefix, saved);
					}
				});
			}
		}
		return inst.tr.commit().thenApplyAsync(new Function<Void, Void>() {
			@Override
			public Void apply(Void a) {
				inst.tr = inst.context.newTransaction();
				return null;
			}
		});
	}

	private static CompletableFuture<Void> pushRange(Instruction inst, CompletableFuture<List<KeyValue>> range) {
		return pushRange(inst, range, null);
	}

	private static CompletableFuture<Void> pushRange(Instruction inst, CompletableFuture<List<KeyValue>> range, byte[] prefixFilter) {
		//System.out.println("Waiting on range data to push...");
		return range.thenApplyAsync(new ListPusher(inst, prefixFilter));
	}

	/**
	 * Pushes the result of a range query onto the stack as a {@code Tuple}
	 */
	private static class ListPusher implements Function<List<KeyValue>, Void> {
		final Instruction inst;
		final byte[] prefixFilter;

		ListPusher(Instruction inst, byte[] prefixFilter) {
			this.inst = inst;
			this.prefixFilter = prefixFilter;
		}
		@Override
		public Void apply(List<KeyValue> list) {
			List<byte[]> o = new LinkedList<byte[]>();
			for(KeyValue kv : list) {
				if(prefixFilter == null || ByteArrayUtil.startsWith(kv.getKey(), prefixFilter)) {
					o.add(kv.getKey());
					o.add(kv.getValue());
				}
			}
			//System.out.println("Added " + o.size() / 2 + " pairs to stack/tuple");
			inst.push(Tuple.fromItems(o).pack());
			return null;
		}
	}

	static class AsynchronousContext extends Context {
		List<KeyValue> operations = null;
		int currentOp = 0;

		AsyncDirectoryExtension directoryExtension = new AsyncDirectoryExtension();

		AsynchronousContext(Database db, byte[] prefix) {
			super(db, prefix);
		}

		@Override
		Context createContext(byte[] prefix) {
			return new AsynchronousContext(this.db, prefix);
		}

		CompletableFuture<Void> processOp(byte[] operation) {
			Tuple tokens = Tuple.fromBytes(operation);
			final Instruction inst = new Instruction(this, tokens);

			/*if(!inst.op.equals("PUSH") && !inst.op.equals("SWAP")) {
				System.out.println(inst.context.preStr + "\t- " + Thread.currentThread().getName() +
				  "\t- OP (" + inst.context.instructionIndex + "):" + inst.op);
			}*/

			if(inst.op.startsWith(DIRECTORY_PREFIX))
				return directoryExtension.processInstruction(inst);
			else {
				return AsyncUtil.composeExceptionally(processInstruction(inst),
					new Function<Throwable, CompletableFuture<Void>>() {
						@Override
						public CompletableFuture<Void> apply(Throwable e) {
							FDBException ex = StackUtils.getRootFDBException(e);
							if(ex != null) {
								StackUtils.pushError(inst, ex);
								return CompletableFuture.completedFuture(null);
							}
							else {
								CompletableFuture<Void> f = new CompletableFuture<Void>();
								f.completeExceptionally(e);
								return f;
							}
						}
					});
			}
		}

		@Override void executeOperations() {
			executeRemainingOperations().join();
		}

		CompletableFuture<Void> executeRemainingOperations() {
			Transaction t = db.createTransaction();

			final Function<Void, CompletableFuture<Void>> processNext = new Function<Void, CompletableFuture<Void>>() {
				@Override
				public CompletableFuture<Void> apply(Void ignore) {
					instructionIndex++;
					return executeRemainingOperations();
				}
			};

			if(operations == null || ++currentOp == operations.size()) {
				return t.getRange(nextKey, endKey, 1000).asList()
				.thenComposeAsync(new Function<List<KeyValue>, CompletableFuture<Void>>() {
					@Override
					public CompletableFuture<Void> apply(List<KeyValue> next) {
						if(next.size() < 1) {
							//System.out.println("No key found after: " + ByteArrayUtil.printable(nextKey.getKey()));
							return CompletableFuture.completedFuture(null);
						}

						operations = next;
						currentOp = 0;
						nextKey = KeySelector.firstGreaterThan(next.get(next.size()-1).getKey());

						return processOp(next.get(0).getValue()).thenComposeAsync(processNext);
					}
				});
			}

			return processOp(operations.get(currentOp).getValue()).thenComposeAsync(processNext);
		}
	}

	static CompletableFuture<StackEntry> popAndWait(Stack stack) {
		StackEntry entry = stack.pop();
		Object item = entry.value;
		if(!(item instanceof CompletableFuture)) {
			return CompletableFuture.completedFuture(entry);
		}
		final int idx = entry.idx;

		@SuppressWarnings("unchecked")
		final CompletableFuture<Object> future = (CompletableFuture<Object>)item;
		CompletableFuture<Object> flattened = flatten(future);

		return flattened.thenApplyAsync(new Function<Object, StackEntry>() {
			@Override
			public StackEntry apply(Object o) {
				return new StackEntry(idx, o);
			}
		});
	}

	private static CompletableFuture<Object> flatten(final CompletableFuture<Object> future) {
		CompletableFuture<Object> f = future.thenApplyAsync(new Function<Object, Object>() {
			@Override
			public Object apply(Object o) {
				if(o == null)
					return "RESULT_NOT_PRESENT".getBytes();
				return o;
			}
		});

		return AsyncUtil.composeExceptionally(f, new Function<Throwable, CompletableFuture<Object>>() {
			@Override
			public CompletableFuture<Object> apply(Throwable t) {
				FDBException e = StackUtils.getRootFDBException(t);
				if(e != null) {
					return CompletableFuture.completedFuture(StackUtils.getErrorBytes(e));
				}

				CompletableFuture<Object> error = new CompletableFuture<Object>();
				error.completeExceptionally(t);
				return error;
			}
		});
	}


	/**
	 * Run a stack-machine based test.
	 */
	public static void main(String[] args) {
		if(args.length < 1)
			throw new IllegalArgumentException("StackTester needs parameters <prefix> <optional_cluster_file>");

		//System.out.println("Prefix: " + args[0]);

		byte[] prefix = args[0].getBytes();

		FDB fdb = FDB.selectAPIVersion(Integer.parseInt(args[1]));
		//ExecutorService executor = Executors.newFixedThreadPool(2);
		Cluster cl = fdb.createCluster(args.length > 2 ? args[2] : null);

		Database db = cl.openDatabase();

		Context c = new AsynchronousContext(db, prefix);
		//System.out.println("Starting test...");
		c.run();
		//System.out.println("Done with test.");

		/*byte[] key = Tuple.from("test_results".getBytes(), 5).pack();
		byte[] bs = db.createTransaction().get(key).get();
		System.out.println("output of " + ByteArrayUtil.printable(key) + " as: " + ByteArrayUtil.printable(bs));*/

		/*fdb.stopNetwork();
		executor.shutdown();*/
	}

}
