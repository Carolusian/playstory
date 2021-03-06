/**
 * feedsView.js
 */

(function(PlayStory, Dashboard, Router) {

    Dashboard.Feeds.FeedsView = function(server, searchView, inboxView) {
        console.log("[Feeds.View] Init feeds past view");
        var self = this,
            bucket = PlayStory.Bucket,
            modelsDef = PlayStory.ModelsDef,
            limit = 10;

        this.pastDOM    =  new Dashboard.Feeds.FeedsPastDOM();
        this.presentDOM =  new Dashboard.Feeds.FeedsPresentDOM();

        server.onReceive(server.urls.listen)
              .map(modelsDef.asFeed)
              .await(
                bucket.collections('feeds').putAsAction
               .and(self.presentDOM.displayNewFeed())
               .then(self.pastDOM.updateCounter)
        ).subscribe();

        server.onReceive(server.urls.last)
            .map(modelsDef.asFeed)
            .await(
                bucket.collections('feeds').asFifo(limit)
               .and(self.pastDOM.displayNewFeed(limit))
        ).subscribe();

        server.onReceive(server.urls.byLevel)
            .map(modelsDef.asFeed)
            .await(
                bucket.collections('feeds').asFifo(limit)
               .and(self.pastDOM.displayNewFeed(limit))
        ).subscribe();

        server.onReceive(server.urls.bookmarks)
            .map(modelsDef.asFeed)
            .await(
                bucket.collections('feeds').asFifo(limit)
               .and(self.pastDOM.displayNewFeed(limit))
        ).subscribe();

        server.onReceive(server.urls.more)
            .map(modelsDef.asFeed)
            .await(
                bucket.collections('feeds').putAsAction
               .and(self.pastDOM.displayPastFeed)
        ).subscribe();

        server.onReceive(server.urls.withContext)
            .map(modelsDef.asFeed)
            .filter(function(feed) {
                var params = Router.matchCurrentRoute('dashboard/past/:project/feed/:id/:limit');
                return (feed.id == params[1]);
            })
            .await(
                bucket.collections('feeds').asFifo(limit)
                      .and(self.pastDOM.displayNewFeed(limit)
                      .then(self.pastDOM.highlightFeed))
        ).subscribe();

        server.onReceive(server.urls.withContext)
            .map(modelsDef.asFeed)
            .filter(function(feed) {
                var params = Router.matchCurrentRoute('dashboard/past/:project/feed/:id/:limit');
                return !(feed.id == params[1]);
            })
            .await(
                bucket.collections('feeds').asFifo(limit)
               .and(self.pastDOM.displayNewFeed(limit))
        ).subscribe();

        server.onReceive('/dashboard/:project/search?*keywords')
            .map(modelsDef.asFeed)
            .await(self.pastDOM.displayNewFeed())
            .subscribe();

        Router.when('dashboard/past/:project/search/*keywords').chain(
            self.pastDOM.clearFeeds,
            searchView.dom.fillSearch,
            server.searchFeeds,
            server.streamFeeds
        );

        var onlyRoutes = function(routes) {
            return function() {
                var matchedRoutes = routes.filter(function(route) {
                    return Router.isMatchCurrentRoute(route);
                });
                return (matchedRoutes.length > 0) && (bucket.collections('feeds').size() > 0);
            };
        };

        var goFeed = function(trigger) {
            return Router.goAsAction('dashboard/past/:project/feed/:id/' + limit,
                                     function(uriPattern, params) {
                                         return uriPattern.replace(':project', params.project)
                                             .replace(':id', params.id);
                                     }, trigger);
        };

        Router.when('dashboard/past/:project').chain(
            searchView.dom.clearSearch,
            bucket.collections('feeds').resetAsAction,
            self.pastDOM.clearFeeds,
            server.closeStream(server.urls.listen),
            server.streamFeeds
        ).and(server.fetchLastFeeds);

        Router.when('dashboard/present/:project').chain(
            searchView.dom.clearSearch,
            server.closeStream(server.urls.listen),
            server.streamFeeds
        );

        Router.when('dashboard/past/:project/level/:level').chain(
            searchView.dom.clearSearch,
            bucket.collections('feeds').resetAsAction,
            self.pastDOM.clearFeeds,
            server.closeStream(server.urls.listen),
            server.streamFeeds,
            server.fetchFeedsByLevel
        );

        Router.when('dashboard/bookmarks').chain(
            searchView.dom.clearSearch,
            bucket.collections('feeds').resetAsAction,
            self.pastDOM.clearFeeds,
            server.fetch(server.urls.bookmarks)
        );

        Router.when('dashboard/past/:project/feed/:id/:limit').chain(
            searchView.dom.clearSearch,
            bucket.collections('feeds').resetAsAction,
            self.pastDOM.clearFeeds,
            server.closeStream(server.urls.listen),
            server.streamFeeds
        ).and(server.fetchFeedWithContext);

        this.lazyInit = Action(function(any, next) {

            When(self.pastDOM.onNewCommentClick)
                .await(self.pastDOM.displayNewComment)
                .subscribe();

            When(self.pastDOM.onBookmarkClick)
                .map(self.pastDOM.newBookmark)
                .await(server.bookmark.then(inboxView.dom.summupStarred))
                .subscribe();

            When(self.pastDOM.onSubmitCommentClick)
                .map(self.pastDOM.newComment)
                .await(server.saveNewComment.then(self.pastDOM.displayComment))
                .subscribe();

            When(self.pastDOM.onBottomPageReach)
                .filter(onlyRoutes(['dashboard/past/:project',
                                    'dashboard/past/:project/level/:level',
                                    'dashboard/past/:project/feed/:id/:level']))
                .map(function() {
                    return Router.currentParams();
                })
                .await(server.fetchMoreFeeds)
                .subscribe();

            When(self.pastDOM.onFeedClick)
                .map(self.pastDOM.clickedFeed)
                .map(function($feed) {
                    return {
                        id: $feed.attr('id'),
                        project: $feed.data('project')
                    };
                }).await(
                    goFeed().and(self.pastDOM.highlightFeed)
                ).subscribe();

            When(self.presentDOM.onFeedClick)
                .map(self.presentDOM.clickedFeed)
                .map(function($feed) {
                    return {
                        id: $feed.attr('id'),
                        project: $feed.data('project')
                    };
                }).await(
                    goFeed(true).and(self.pastDOM.highlightFeed)
                ).subscribe();

            When(self.pastDOM.onMoreFeedsClick)
                .filter(onlyRoutes(['dashboard/past/:project',
                                    'dashboard/past/:project/level/:level',
                                    'dashboard/past/:project/feed/:id/:level']))
                .map(function() {
                    var paramsAsArray = [];
                    var params = Router.currentParams();
                    for(var p in params) {
                        paramsAsArray.push(params[p]);
                    }
                    return paramsAsArray;
                })
                .await(server.fetchLastFeeds.then(self.pastDOM.resetMoreFeeds))
                .subscribe();

            next(any);
        });
    };

})(window.PlayStory,
   window.PlayStory.Init.Dashboard,
   window.PlayStory.Router);