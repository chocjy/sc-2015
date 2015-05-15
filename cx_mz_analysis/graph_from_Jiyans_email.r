# Load data from Jiyan's email
dat <- read.table('Jiyan_data.csv', sep=',', header=T)

# make mz by tau plot
require(ggplot2)

p <- ggplot(data=dat, aes(x=mz, y=tau, fill=levscore, size=levscore)) + 
		geom_point(color='gray', shape=21, alpha=0.5) + 
		theme_bw() + 
		scale_fill_gradientn(colours=rainbow(8)) + 
		scale_size_continuous(range=c(3,7)) + 
		geom_text(aes(label=format(mz, digits=7)), 
			  	  size=2, 
			  	  position=position_jitter(height=1, width=1))
			  	  
print(p)

# aggregate by integer mz to collapse related variables
datagg <- aggregate(cbind(tau, mz, levscore) ~ intmz, dat, FUN=mean)

# make same plot on aggregate data

p2 <- ggplot(data=datagg, aes(x=mz, y=tau, fill=levscore, size=levscore)) + 
		geom_point(color='gray', shape=21, alpha=0.5) + 
		theme_bw() + 
		scale_fill_gradientn(colours=rainbow(8)) + 
		scale_size_continuous(range=c(3,7)) + 
		geom_text(aes(label=format(mz, digits=7)), 
			  	  size=2, 
			  	  position=position_jitter(height=1, width=1))
			  	  
print(p2)


# find distance matrix between mz values
distmat <- as.matrix(dist(datagg$mz))
rownames(distmat) <- datagg$mz
distmat <- as.dist(distmat)
