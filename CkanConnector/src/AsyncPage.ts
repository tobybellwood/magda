export interface CreateAsyncPage<T> {
    (): Promise<AsyncPage<T>>
}

export default class AsyncPage<T> {
    public readonly requestNextPage: CreateAsyncPage<T>;
    public readonly data: T;
    public readonly hasData: boolean;

    static create<T>(next: (data: T) => Promise<T>): AsyncPage<T> {
        const nextPage: (lastPage: T) => Promise<AsyncPage<T>> = (lastPage: T) => {
            const nextPromise = next(lastPage);
            if (!nextPromise) {
                // There is no next page.
                return Promise.resolve(new AsyncPage(undefined, false, undefined));
            }
            return nextPromise.then(thisPage => {
                return new AsyncPage(thisPage, true, () => nextPage(thisPage));
            });
        };

        return new AsyncPage(undefined, false, () => nextPage(undefined));
    }

    constructor(data: T, hasData: boolean, requestNextPage: CreateAsyncPage<T>) {
        this.data = data;
        this.hasData = hasData;
        this.requestNextPage = requestNextPage;
    }

    map<TResult>(selector: (data: T) => TResult): AsyncPage<TResult> {
        async function loadNextPage(pager: AsyncPage<T>): Promise<AsyncPage<TResult>> {
            const nextPage = await pager.requestNextPage();

            const data = nextPage.hasData ? selector(nextPage.data) : undefined;
            const loadNextPageFunction = nextPage.requestNextPage ? () => loadNextPage(nextPage) : undefined;

            return new AsyncPage<TResult>(data, nextPage.hasData, loadNextPageFunction);
        }

        return new AsyncPage<TResult>(undefined, false, () => loadNextPage(this));
    }
}

export function forEachAsync<T>(page: AsyncPage<T[]>, maxConcurrency: number, callbackFn: (data: T) => Promise<void>): Promise<void> {
    let currentPromise: Promise<AsyncPage<T[]>> = undefined;
    let currentIndex = 0;
    let resultIndex = 0;

    async function getNext(): Promise<{value: T, index: number}> {
        while (page.requestNextPage && (!page.hasData || currentIndex >= page.data.length)) {
            if (!currentPromise) {
                currentPromise = page.requestNextPage().then(nextPage => {
                    currentIndex = 0;
                    return nextPage;
                });
            }            
            page = await currentPromise;
            currentPromise = undefined;
        }

        if (!page.hasData) {
            return undefined;
        }

        return {
            value: page.data[currentIndex++],
            index: resultIndex++
        };
    }

    async function transformNext(): Promise<void> {
        const next = await getNext();
        if (!next) {
            return;
        }

        await callbackFn(next.value);

        // Start the next value.
        return transformNext();
    }

    const promises = [];

    for (var i = 0; i < maxConcurrency; ++i) {
        promises.push(transformNext());
    }

    return Promise.all(promises).then(results => { return; });
}
