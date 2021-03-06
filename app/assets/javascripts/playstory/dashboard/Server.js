/**
 * Server.js
 */

(function(Dashboard, PlayStory, PlayRoutes, RouterUtils) {

    Dashboard.Server = function() {
        console.log("[Server] Init Server");

        this.urls = {
            listen:      PlayRoutes.controllers.Dashboard.listen(':project').url,
            last:        PlayRoutes.controllers.Dashboard.last(':project').url,
            byLevel:     PlayRoutes.controllers.Dashboard.byLevel(':project', ':level').url,
            bookmarks:   PlayRoutes.controllers.Dashboard.bookmarks().url,
            more:        PlayRoutes.controllers.Dashboard.more(':project', ':id', ':limit').url,
            withContext: PlayRoutes.controllers.Dashboard.withContext(':project', ':id', ':limit').url,
            inbox:       PlayRoutes.controllers.Dashboard.inbox(':project').url,
            bookmark:    PlayRoutes.controllers.Dashboard.bookmark(':project', ':id').url,
            comment:     PlayRoutes.controllers.Dashboard.comment(':project', ':id').url
        };

        var self = this,
            bucket = PlayStory.Bucket,
            subscriptions = [],
            sources = [];

        var _subscribe = function(uri, callback) {
            subscriptions[uri] = subscriptions[uri] || [];
            subscriptions[uri].push(callback);
        };

        var _alreadyConnected = function(uri) {
            for(var sourceName in sources) {
                if(RouterUtils.routeAsRegex(uri).test(sourceName) && sources[sourceName] != null) {
                    return true;
                }
            }
            return false;
        };

        var _streamChunks = function(chunk) {
            var subscribers = [];
            if(chunk.src) {
                for(var uri in subscriptions) {
                    if(RouterUtils.routeAsRegex(uri).test(chunk.src)) {
                        subscribers = subscriptions[uri] || [];
                        break;
                    }
                }

                if(subscribers.length == 0) {
                    console.log("[Server] No subscribers found for " + chunk.src);
                }

                subscribers.forEach(function(s) {
                    s(chunk);
                });
            } else console.log("[Server] No source specified");
        };

        var _closeStream = function(uri) {
            if(uri) {
                for(var sourceName in sources) {
                    if(RouterUtils.routeAsRegex(uri).test(sourceName) && sources[sourceName] != null) {
                        console.log('[Server] Close ' + sourceName + ' -> ' + uri);
                        var source = sources[sourceName];
                        sources[sourceName] = null;
                        source.close();
                    }
                }
            }
            return false;
        };

        this.fromPulling = function(any) {
            _streamChunks(JSON.parse(any.data));
        };

        this.onReceive = function(uri) {
            console.log("[Server] Subscribe to " + uri);
            return When(function(next) {
                _subscribe(uri, next);
            });
        };

        this.onReceiveFromTemplate = function(modelName) {
            return this.onReceive('/template')
                       .filter(function(model) {
                           return model.name == modelName;
                       })
                       .map(function(model) {
                           return model.data;
                       });
        };

        this.stream = function(uriPattern, buildURI) {
            return Action(function(params, next) {
                var uri = buildURI.apply(null, [uriPattern].concat(arguments[0]));
                if(!_alreadyConnected(uri)) {
                    console.log("[Server] Bind to stream " + uri);
                    var source = new EventSource(uri);
                    source.onmessage = function(chunk) {
                        _streamChunks(JSON.parse(chunk.data));
                    };
                    sources[uri] = source;
                }
                next(params);
            });
        };

        this.streamFeeds = this.stream(this.urls.listen, function(uriPattern, project) {
            return uriPattern.replace(':project', project);
        }),

        this.closeStream = function(uriPattern) {
            return Action(function(project, next) {
                var nextURI = uriPattern.replace(':project', project);
                if(!_alreadyConnected(nextURI)) {
                    _closeStream(uriPattern);
                }
                next(project);
            });
        };

        this.fetch = function(uriPattern, buildURI) {
            return Action(function(params, next) {
                var uri = uriPattern;
                if(buildURI) uri = buildURI.apply(null, [uriPattern].concat(arguments[0]));
                $.ajax({
                    url: uri,
                    dataType: 'json',
                    success: function(feeds) {
                        feeds.forEach(function(feed) {
                            _streamChunks(feed);
                        });
                        next(params);
                    },
                    error: function() {
                        next(params);
                    }
                });
            });
        };

        this.fetchInbox = this.fetch(this.urls.inbox, function(uriPattern, project) {
            return uriPattern.replace(':project', project);
        });

        this.fetchFeedWithContext = this.fetch(this.urls.withContext, function(uriPattern, project, id, limit) {
            return uriPattern.replace(':project', project)
                             .replace(':id', id)
                             .replace(':limit', limit);
        });

        this.fetchFeedsByLevel = this.fetch(this.urls.byLevel, function(uriPattern, project, level) {
            return uriPattern.replace(':project', project)
                             .replace(':level', level);
        });

        this.fetchLastFeeds = this.fetch(this.urls.last, function(uriPattern, project) {
            return uriPattern.replace(':project', project);
        });

        this.fetchMoreFeeds = this.fetch(this.urls.more, function(uriPattern, params) {
            var lastFeed = bucket.collections('feeds').last();
            var uri = uriPattern.replace(':project', params.project)
                                .replace(':id', lastFeed.id)
                                .replace(':limit', 6);

            if(params.level) {
                uri += '?level=' + params.level;
            }
            return uri;
        });

        this.searchFeeds = this.fetch('/dashboard/:project/search?:keywords', function(uriPattern, project, keywords, level) {
            var uri = uriPattern.replace(':project', project)
                                .replace(':keywords', keywords);
            if(level) uri += '?level=' + level;
            return uri;
        });

        this.bookmark = Action(function(bookmark, next) {
            $.ajax({
                url: PlayRoutes.controllers.Dashboard.bookmark(bookmark.project, bookmark.feed).url,
                type: 'POST',
                dataType: 'json',
                success: function() {
                    next(bookmark);
                }
            });
        });

        this.saveNewComment = Action(function(comment, next) {
            console.log("[Server] Save new comment");
            var authorId = bucket.models('user').get().id;

            $.ajax({
                url: PlayRoutes.controllers.Dashboard.comment(comment.project, comment.id).url,
                type: 'POST',
                data: JSON.stringify({ author: authorId, message: comment.msg}),
                dataType: 'json',
                contentType: 'application/json',
                success: function() {
                    next(comment);
                }
            });
        });
    };
})(window.PlayStory.Init.Dashboard,
   window.PlayStory,
   window.PlayRoutes,
   window.RouterUtils);