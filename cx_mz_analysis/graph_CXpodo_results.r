# Load full CX results from Jiyan's data (set nrows and colClasses for mem efficiency)
setwd('~/Documents/Personal/LBNL/mantissa_randLA/')

dat <- read.table('taumz_new_id_old_id_lev.txt', sep=',', header=F, 
				  colClasses=c('integer', 'NULL', 'numeric', 'NULL', 'NULL', 'numeric'), 
				  nrows=8258911, comment.char="")
names(dat) <- c('tau', 'mz', 'lev')

# Focus only on ions with leverage scores at least 0.1 SDs above the mean
bigs <- dat[dat$lev > 0.1*sd(dat$lev),]


# Make tau, mz plot 
quartz(height=6, width=16)
p <- ggplot(data=bigs, aes(x=mz, y=tau, alpha=lev, color=lev, size=lev)) + 
		geom_point() + 
		theme_bw() + 
		scale_color_continuous(low='black', high='red') + 
		scale_alpha_continuous(range=c(0.3, 0.6)) + 
		xlab('m/z, Da') +
		ylab('drift time, msec')
p
quartz.save('tau_mz_scatter.pdf', type='pdf')


# Zoom in on region
quartz(height=6, width = 6)
p + xlim(c(381, 381.4)) + ylim(c(54, 63))
quartz.save('zoom381_54.pdf', type='pdf')

# Zoom in on other region
quartz(height=6, width = 6)
p + xlim(c(302, 304)) + ylim(c(45, 62))
quartz.save('zoom302_45.pdf', type='pdf')

# Zoom in on yet another region
quartz(height=6, width = 6)
p + scale_x_continuous(breaks=seq(432, 445), limits=c(432, 442)) + ylim(c(60, 80))
quartz.save('zoom_432_60.pdf', type='pdf')