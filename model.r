library(rpart)
library(rpart.plot)
library(data.table)
library(reshape)
library(dplyr)
library(partykit)
source(file="git/utils.r")

df.pat <- fread("/tmp/endotype3.csv")
df.icd <- fread("/tmp/icd3.csv")
df.loinc <- fread("/tmp/loinc3.csv")
df.mdctn <- fread("/tmp/mdctn3.csv")

# df.loinc.wide <- reshape(df.loinc, idvar = "encounter_num", timevar = "loinc_concept", direction = "wide")
# df.mdctn.wide <- reshape(df.loinc, idvar = "encounter_num", timevar = c("mdctn_cd", "mdctn_modifier"), direction = "wide")
df.icd$icd <- TRUE
df.icd.wide <- reshape(df.icd, idvar = "encounter_num", timevar = "icd_code", direction = "wide")

df.pat.2 <- df.pat[,c("encounter_num", "inout_cd", "pre_ed", "post_ed", "sex_cd", "race_cd", "pm25_7da", "age")]
df.pat.2$age <- binAge(df.pat.2$age)
df.pat.2$race_cd <- as.factor(df.pat.2$race_cd)
df.pat.2$pre_ed <- binEdVisits(df.pat.2$pre_ed)
df.pat.2$post_ed <- binEdVisits(df.pat.2$post_ed)
df.pat.2$sex_cd <- as.factor(df.pat.2$sex_cd)
df.pat.2$inout_cd <- as.factor(df.pat.2$inout_cd)

#df <- merge(merge(df.pat.2, df.icd.wide, by = "encounter_num"), df.loinc.wide, by = "encounter_num")
df <- merge(df.pat.2, df.icd.wide, by = "encounter_num")

df.colnames <- colnames(df)
#loinc.colnames <- paste0("`", df.colnames[substr(df.colnames,0,17) == "loinc_nval.LOINC:" | substr(df.colnames,0,4) == "icd."], "`")
icd.colnames <- df.colnames[substr(df.colnames,0,4) == "icd."]
icd.colnames.quoted <- paste0("`", icd.colnames, "`")
loinc.form <- paste(icd.colnames.quoted, collapse = " + ")
formstr <- paste("post_ed ~ inout_cd + pre_ed + sex_cd + race_cd + age + pm25_7da", 
              loinc.form, sep = " + ")
form <- as.formula(formstr)
#df.scaled <- df %>% mutate_if(is.numeric, scale)

#m <- rpart(form, data = df.scaled, 
#           control=rpart.control(cp=0.003))
m <- rpart(form, data = df, 
           control=rpart.control(cp=0.003))
print(m)
rpart.plot(m)
#printcp(m)
saveRDS(as.party(m), file="model.RData")
saveRDS(icd.colnames, file="colnames.RData")