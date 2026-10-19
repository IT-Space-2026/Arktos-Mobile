package com.arktosbrowser.mobile.adblock

import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.source.HostsDataSourceProvider
import com.arktosbrowser.mobile.adblock.source.HostsResult
import com.arktosbrowser.mobile.adblock.util.BloomFilter
import com.arktosbrowser.mobile.adblock.util.DefaultBloomFilter
import com.arktosbrowser.mobile.adblock.util.DelegatingBloomFilter
import com.arktosbrowser.mobile.adblock.util.`object`.JvmObjectStore
import com.arktosbrowser.mobile.adblock.util.`object`.ObjectStore
import com.arktosbrowser.mobile.adblock.util.hash.MurmurHashHostAdapter
import com.arktosbrowser.mobile.adblock.util.hash.MurmurHashStringAdapter
import com.arktosbrowser.mobile.database.adblock.Host
import com.arktosbrowser.mobile.database.adblock.HostsRepository
import com.arktosbrowser.mobile.database.adblock.HostsRepositoryInfo
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.extensions.toast
import com.arktosbrowser.mobile.log.Logger
import android.app.Application
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AdBlocker] that is backed by a [BloomFilter].
 *
 * @param logger The logger used to log status.
 * @param hostsDataSourceProvider The provider that provides the data source used to populate the
 * bloom filter and [hostsRepository].
 * @param hostsRepository The long term store for blocked hosts.
 * @param databaseScheduler The scheduler used to communicate with the database asynchronously.
 */
@Singleton
class BloomFilterAdBlocker @Inject constructor(
    private val logger: Logger,
    private val hostsDataSourceProvider: HostsDataSourceProvider,
    private val hostsRepository: HostsRepository,
    private val hostsRepositoryInfo: HostsRepositoryInfo,
    private val application: Application,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) : AdBlocker {

    private val bloomFilter: DelegatingBloomFilter<Host> = DelegatingBloomFilter()
    private val objectStore: ObjectStore<DefaultBloomFilter<Host>> = JvmObjectStore(application, MurmurHashStringAdapter())

    private val compositeDisposable = CompositeDisposable()

    init {
        populateAdBlockerFromDataSource(forceRefresh = false)
    }

    /**
     * Force the ad blocker to (re)populate its internal hosts filter from the provided hosts data
     * source.
     */
    fun populateAdBlockerFromDataSource(forceRefresh: Boolean) {
        compositeDisposable.clear()
        compositeDisposable += Single.fromCallable(hostsDataSourceProvider::createHostsDataSource)
            .flatMapMaybe { hostsDataSource ->
                loadStoredBloomFilter().filter {
                    // Force a new hosts request if the hosts are out of date or if the repo has no hosts.
                    hostsRepositoryInfo.identity == hostsDataSource.identifier()
                        && hostsRepository.hasHosts()
                        && !forceRefresh
                }.switchIfEmpty(
                    hostsDataSourceProvider
                        .createHostsDataSource()
                        .loadHosts()
                        .flatMapMaybe {
                            when (it) {
                                is HostsResult.Success -> Maybe.just(it.hosts)
                                is HostsResult.Failure -> Maybe.empty<List<Host>>().doOnComplete {
                                    logger.log(TAG, "Unable to load hosts", it.cause)
                                }
                            }
                        }
                        .flatMapSingleElement {
                            // Clear out the old hosts and bloom filter now that we have the new hosts.
                            hostsRepository.removeAllHosts()
                                .andThen(hostsRepository.addHosts(it))
                                .andThen(createAndSaveBloomFilter(it))
                                .doOnSuccess {
                                    hostsRepositoryInfo.identity = hostsDataSource.identifier()
                                }
                        }
                )
            }
            .filter {
                // If we were unsuccessful in loading hosts and we don't have hosts in the repo, don't
                // allow initialization, as false positives will result in bad browsing experience.
                hostsRepository.hasHosts()
            }.subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = {
                    bloomFilter.delegate = it
                    logger.log(TAG, "Finished loading bloom filter")
                },
                onComplete = {
                    application.toast(R.string.ad_block_load_failure)
                }
            )
    }

    private fun loadStoredBloomFilter(): Maybe<BloomFilter<Host>> = Maybe.fromCallable {
        objectStore.retrieve(BLOOM_FILTER_KEY)
    }

    private fun createAndSaveBloomFilter(hosts: List<Host>): Single<BloomFilter<Host>> = Single.fromCallable {
        logger.log(TAG, "Constructing bloom filter from list")

        val bloomFilter = DefaultBloomFilter(
            numberOfElements = hosts.size,
            falsePositiveRate = 0.01,
            hashingAlgorithm = MurmurHashHostAdapter()
        )
        bloomFilter.putAll(hosts)
        objectStore.store(BLOOM_FILTER_KEY, bloomFilter)

        bloomFilter
    }

    override fun isAd(url: String): Boolean {
        val domain = try {
            getDomainName(url)
        } catch (exception: URISyntaxException) {
            logger.log(TAG, "URL '$url' is invalid", exception)
            return false
        }

        val mightBeOnBlockList = bloomFilter.mightContain(domain)

        return if (mightBeOnBlockList) {
            val isOnBlockList = hostsRepository.containsHost(domain)
            if (isOnBlockList) {
                logger.log(TAG, "URL '$url' is an ad")
            } else {
                logger.log(TAG, "False positive for $url")
            }

            isOnBlockList
        } else {
            false
        }
    }

    /**
     * Returns the probable domain name for a given URL
     *
     * @param url the url to parse
     * @return returns the domain
     * @throws URISyntaxException throws an exception if the string cannot form a URI
     */
    @Throws(URISyntaxException::class)
    private fun getDomainName(url: String): Host {
        val host = url.indexOf('/', 8)
            .takeIf { it != -1 }
            ?.let(url::take)
            ?: url

        val uri = URI(host)
        val domain = uri.host ?: return Host(host)

        return Host(if (domain.startsWith("www.")) {
            domain.substring(4)
        } else {
            domain
        })
    }

    companion object {
        private const val TAG = "BloomFilterAdBlocker"
        private const val BLOOM_FILTER_KEY = "AdBlockingBloomFilter"
    }

}
